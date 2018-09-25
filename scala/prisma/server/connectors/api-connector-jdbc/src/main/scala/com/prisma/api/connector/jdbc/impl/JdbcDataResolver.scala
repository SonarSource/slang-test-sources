package com.prisma.api.connector.jdbc.impl

import com.prisma.api.connector._
import com.prisma.api.connector.jdbc.Metrics
import com.prisma.api.connector.jdbc.database.{JdbcActionsBuilder, SlickDatabase}
import com.prisma.gc_values._
import com.prisma.shared.models._

import scala.concurrent.{ExecutionContext, Future}

case class JdbcDataResolver(
    project: Project,
    slickDatabase: SlickDatabase,
    schemaName: Option[String]
)(implicit ec: ExecutionContext)
    extends DataResolver {

  val queryBuilder = JdbcActionsBuilder(
    schemaName = schemaName.getOrElse(project.id),
    slickDatabase = slickDatabase
  )

  override def getModelForGlobalId(globalId: CuidGCValue): Future[Option[Model]] = {
    val query = queryBuilder.getModelForGlobalId(project.schema, globalId)
    performWithTiming("getModelForGlobalId", slickDatabase.database.run(query))
  }

  override def getNodeByWhere(where: NodeSelector, selectedFields: SelectedFields): Future[Option[PrismaNode]] = {
    performWithTiming("getNodeByWhere", slickDatabase.database.run(queryBuilder.getNodeByWhere(where, selectedFields)))
  }

  override def getNodes(model: Model, args: Option[QueryArguments], selectedFields: SelectedFields): Future[ResolverResult[PrismaNode]] = {
    val query = queryBuilder.getNodes(model, args, selectedFields)
    performWithTiming("loadModelRowsForExport", slickDatabase.database.run(query))
  }

  override def getRelatedNodes(
      fromField: RelationField,
      fromNodeIds: Vector[IdGCValue],
      args: Option[QueryArguments],
      selectedFields: SelectedFields
  ): Future[Vector[ResolverResult[PrismaNodeWithParent]]] = {
    val query = queryBuilder.getRelatedNodes(fromField, fromNodeIds, args, selectedFields)
    performWithTiming("resolveByRelation", slickDatabase.database.run(query))
  }

  override def getScalarListValues(model: Model, field: ScalarField, args: Option[QueryArguments] = None): Future[ResolverResult[ScalarListValues]] = {
    val query = queryBuilder.getScalarListValues(model, field, args)
    performWithTiming("loadListRowsForExport", slickDatabase.database.run(query))
  }

  override def getScalarListValuesByNodeIds(model: Model, listField: ScalarField, nodeIds: Vector[IdGCValue]): Future[Vector[ScalarListValues]] = {
    val query = queryBuilder.getScalarListValuesByNodeIds(listField, nodeIds)
    performWithTiming("batchResolveScalarList", slickDatabase.database.run(query))
  }

  override def getRelationNodes(relationId: String, args: Option[QueryArguments] = None): Future[ResolverResult[RelationNode]] = {
    val relation = project.schema.relations.find(_.relationTableName == relationId).get
    val query    = queryBuilder.getRelationNodes(relation, args)
    performWithTiming("loadRelationRowsForExport", slickDatabase.database.run(query))
  }

  override def countByTable(table: String, whereFilter: Option[Filter] = None): Future[Int] = {
    val actualTable = project.schema.getModelByName(table) match {
      case Some(model) => model.dbName
      case None        => table
    }
    val query = queryBuilder.countAllFromTable(actualTable, whereFilter)
    performWithTiming("countByTable", slickDatabase.database.run(query))
  }

  override def countByModel(model: Model, args: Option[QueryArguments]) = {
    val query = queryBuilder.countFromModel(model, args)
    performWithTiming("countByModel", slickDatabase.database.run(query))
  }

  protected def performWithTiming[A](name: String, f: => Future[A]): Future[A] = Metrics.sqlQueryTimer.timeFuture(project.id, name) { f }

}
