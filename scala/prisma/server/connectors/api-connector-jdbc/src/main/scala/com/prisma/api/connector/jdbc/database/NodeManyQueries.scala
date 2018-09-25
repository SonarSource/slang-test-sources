package com.prisma.api.connector.jdbc.database

import com.prisma.api.connector._
import com.prisma.gc_values.IdGCValue
import com.prisma.shared.models.{Model, RelationField}
import org.jooq.{Record, SelectForUpdateStep}
import slick.jdbc.PositionedParameters

trait NodeManyQueries extends BuilderBase with FilterConditionBuilder with CursorConditionBuilder with OrderByClauseBuilder with LimitClauseBuilder {
  import slickDatabase.profile.api._

  def getNodes(model: Model, queryArguments: Option[QueryArguments], selectedFields: SelectedFields): DBIO[ResolverResult[PrismaNode]] = {
    val query = modelQuery(model, queryArguments, selectedFields)

    queryToDBIO(query)(
      setParams = pp => SetParams.setQueryArgs(pp, queryArguments),
      readResult = { rs =>
        val result = rs.readWith(readsPrismaNode(model, selectedFields.scalarNonListFields))
        ResolverResult(queryArguments, result)
      }
    )
  }

  def countFromModel(model: Model, queryArguments: Option[QueryArguments]): DBIO[Int] = {
    val baseQuery = modelQuery(model, queryArguments, SelectedFields(Set(model.idField_!)))
    val query     = sql.selectCount().from(baseQuery)

    queryToDBIO(query)(
      setParams = pp => SetParams.setQueryArgs(pp, queryArguments),
      readResult = { rs =>
        val _                      = rs.next()
        val count                  = rs.getInt(1)
        val SkipAndLimit(_, limit) = skipAndLimitValues(queryArguments) // this returns the actual limit increased by 1 to enable hasNextPage for pagination
        val result = limit match {
          case Some(limit) => if (count > (limit - 1)) count - 1 else count
          case None        => count
        }
        Math.max(result, 0)
      }
    )
  }

  private def modelQuery(model: Model, queryArguments: Option[QueryArguments], selectedFields: SelectedFields): SelectForUpdateStep[Record] = {
    val condition       = buildConditionForFilter(queryArguments.flatMap(_.filter))
    val cursorCondition = buildCursorCondition(queryArguments, model)
    val order           = orderByForModel(model, topLevelAlias, queryArguments)
    val skipAndLimit    = skipAndLimitValues(queryArguments)
    val jooqFields      = selectedFields.includeOrderBy(queryArguments).scalarNonListFields.map(aliasColumn)

    val base = sql
      .select(jooqFields.toVector: _*)
      .from(modelTable(model).as(topLevelAlias))
      .where(condition, cursorCondition)
      .orderBy(order: _*)
      .offset(intDummy)

    skipAndLimit.limit match {
      case Some(_) => base.limit(intDummy)
      case None    => base
    }
  }

  def getRelatedNodes(
      fromField: RelationField,
      fromNodeIds: Vector[IdGCValue],
      queryArguments: Option[QueryArguments],
      selectedFields: SelectedFields
  ): DBIO[Vector[ResolverResult[PrismaNodeWithParent]]] = {

    if (isMySql && queryArguments.exists(_.isWithPagination)) {
      selectAllFromRelatedWithPaginationForMySQL(fromField, fromNodeIds, queryArguments, selectedFields)
    } else {
      val builder = RelatedModelsQueryBuilder(slickDatabase, schemaName, fromField, queryArguments, fromNodeIds, selectedFields.includeOrderBy(queryArguments))
      val query   = if (queryArguments.exists(_.isWithPagination)) builder.queryWithPagination else builder.queryWithoutPagination

      queryToDBIO(query)(
        setParams = { pp =>
          fromNodeIds.foreach(pp.setGcValue)
          queryArguments.foreach { arg =>
            arg.filter.foreach(filter => SetParams.setFilter(pp, filter))

            SetParams.setCursor(pp, arg)

            if (arg.isWithPagination) {
              val params = limitClauseForWindowFunction(queryArguments)
              pp.setInt(params._1)
              pp.setInt(params._2)
            }
          }
        },
        readResult = { rs =>
          val result              = rs.readWith(readPrismaNodeWithParent(fromField, selectedFields.scalarNonListFields))
          val itemGroupsByModelId = result.groupBy(_.parentId)
          fromNodeIds.map { id =>
            itemGroupsByModelId.find(_._1 == id) match {
              case Some((_, itemsForId)) => ResolverResult(queryArguments, itemsForId, parentModelId = Some(id))
              case None                  => ResolverResult(Vector.empty[PrismaNodeWithParent], hasPreviousPage = false, hasNextPage = false, parentModelId = Some(id))
            }
          }
        }
      )
    }
  }

  private def selectAllFromRelatedWithPaginationForMySQL(
      fromField: RelationField,
      fromModelIds: Vector[IdGCValue],
      queryArguments: Option[QueryArguments],
      selectedFields: SelectedFields
  ): DBIO[Vector[ResolverResult[PrismaNodeWithParent]]] = {
    require(queryArguments.exists(_.isWithPagination))
    val builder = RelatedModelsQueryBuilder(slickDatabase, schemaName, fromField, queryArguments, fromModelIds, selectedFields)

    SimpleDBIO { ctx =>
      val baseQuery        = "(" + builder.mysqlHack.getSQL + ")"
      val distinctModelIds = fromModelIds.distinct
      val queries          = Vector.fill(distinctModelIds.size)(baseQuery)
      val query            = queries.mkString(" union all ")

      val ps = ctx.connection.prepareStatement(query)
      val pp = new PositionedParameters(ps)

      distinctModelIds.foreach { id =>
        pp.setGcValue(id)

        queryArguments.foreach { arg =>
          arg.filter.foreach(filter => SetParams.setFilter(pp, filter))

          SetParams.setCursor(pp, arg)

          if (arg.isWithPagination) {
            val skipAndLimit = skipAndLimitValues(queryArguments)
            skipAndLimit.limit.foreach(pp.setInt)
            pp.setInt(skipAndLimit.skip)
          }
        }
      }

      val rs                  = ps.executeQuery()
      val result              = rs.readWith(readPrismaNodeWithParent(fromField, selectedFields.scalarNonListFields))
      val itemGroupsByModelId = result.groupBy(_.parentId)
      fromModelIds.map { id =>
        itemGroupsByModelId.find(_._1 == id) match {
          case Some((_, itemsForId)) => ResolverResult(queryArguments, itemsForId, parentModelId = Some(id))
          case None                  => ResolverResult(Vector.empty[PrismaNodeWithParent], hasPreviousPage = false, hasNextPage = false, parentModelId = Some(id))
        }
      }
    }
  }
}
