package com.prisma.api.connector.jdbc.database

import com.prisma.api.connector.{Filter, NodeSelector, PrismaNode, SelectedFields}
import com.prisma.gc_values.IdGCValue
import com.prisma.shared.models.{Model, RelationField, Schema}
import org.jooq.impl.DSL.{field, name}
import org.jooq.{Condition, Record1, SelectConditionStep}

import scala.concurrent.ExecutionContext
import scala.language.existentials

trait NodeSingleQueries extends BuilderBase with NodeManyQueries with FilterConditionBuilder {
  import slickDatabase.profile.api._

  def getModelForGlobalId(schema: Schema, idGCValue: IdGCValue): DBIO[Option[Model]] = {
    val query = sql
      .select(relayStableIdentifierColumn)
      .from(relayTable)
      .where(relayIdColumn.equal(placeHolder))

    queryToDBIO(query)(
      setParams = pp => pp.setGcValue(idGCValue),
      readResult = { rs =>
        rs.readWith(readStableModelIdentifier).headOption.map { stableModelIdentifier =>
          schema.getModelByStableIdentifier_!(stableModelIdentifier.trim)
        }
      }
    )
  }

  def getNodeByWhere(where: NodeSelector): DBIO[Option[PrismaNode]] = getNodeByWhere(where, SelectedFields.all(where.model))

  def getNodeByWhere(where: NodeSelector, selectedFields: SelectedFields): DBIO[Option[PrismaNode]] = {
    val model      = where.model
    val jooqFields = selectedFields.scalarNonListFields.map(modelColumn)
    val query = sql
      .select(jooqFields.toVector: _*)
      .from(modelTable(model))
      .where(modelColumn(where.field).equal(placeHolder))

    queryToDBIO(query)(
      setParams = pp => pp.setGcValue(where.fieldGCValue),
      readResult = rs => {
        val fieldsToRead = selectedFields.scalarNonListFields
        rs.readWith(readsPrismaNode(model, fieldsToRead)).headOption
      }
    )
  }

  def getNodeIdByWhere(where: NodeSelector): DBIO[Option[IdGCValue]] = {
    val model = where.model
    val query = sql
      .select(idField(model))
      .from(modelTable(model))
      .where(modelColumn(where.field).equal(placeHolder))

    queryToDBIO(query)(
      setParams = _.setGcValue(where.fieldGCValue),
      readResult = _.readWith(readNodeId(model)).headOption
    )
  }

  def getNodeIdByParentId(parentField: RelationField, parentId: IdGCValue)(implicit ec: ExecutionContext): DBIO[Option[IdGCValue]] = {
    getNodeIdsByParentIds(parentField, Vector(parentId)).map(_.headOption)
  }

  def getNodeIdsByParentIds(parentField: RelationField, parentIds: Vector[IdGCValue]): DBIO[Vector[IdGCValue]] = {
    val model = parentField.relatedModel_!
    val q: SelectConditionStep[Record1[AnyRef]] = sql
      .select(idField(model))
      .from(modelTable(model))
      .where(parentIdCondition(parentField, parentIds))

    queryToDBIO(q)(
      setParams = pp => parentIds.foreach(pp.setGcValue),
      readResult = rs => rs.readWith(readNodeId(model))
    )
  }

  def getNodeIdsByFilter(model: Model, filter: Option[Filter]): DBIO[Vector[IdGCValue]] = {
    val aliasedTable    = modelTable(model).as(topLevelAlias)
    val filterCondition = buildConditionForFilter(filter)
    val query           = sql.select(field(name(topLevelAlias, model.dbNameOfIdField_!))).from(aliasedTable).where(filterCondition)

    queryToDBIO(query)(
      setParams = pp => SetParams.setFilter(pp, filter),
      readResult = rs => rs.readWith(readNodeId(model))
    )
  }

  def getNodeIdByParentIdAndWhere(parentField: RelationField, parentId: IdGCValue, where: NodeSelector): DBIO[Option[IdGCValue]] = {
    val model                 = parentField.relatedModel_!
    val nodeSelectorCondition = modelColumn(where.field).equal(placeHolder)
    val q: SelectConditionStep[Record1[AnyRef]] = sql
      .select(idField(model))
      .from(modelTable(model))
      .where(parentIdCondition(parentField), nodeSelectorCondition)

    queryToDBIO(q)(
      setParams = { pp =>
        pp.setGcValue(parentId)
        pp.setGcValue(where.fieldGCValue)
      },
      readResult = rs => rs.readWith(readNodeId(model)).headOption
    )
  }

  private def parentIdCondition(parentField: RelationField): Condition = parentIdCondition(parentField, Vector(1))

  private def parentIdCondition(parentField: RelationField, parentIds: Vector[Any]): Condition = {
    val relation      = parentField.relation
    val childIdField  = relationColumn(relation, parentField.oppositeRelationSide)
    val parentIdField = relationColumn(relation, parentField.relationSide)
    val subSelect = sql
      .select(childIdField)
      .from(relationTable(relation))
      .where(parentIdField.in(placeHolders(parentIds)))

    idField(parentField.relatedModel_!).in(subSelect)
  }
}
