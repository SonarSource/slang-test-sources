package com.prisma.api.connector

import com.prisma.gc_values.{IdGCValue, ListGCValue}
import com.prisma.shared.models.ModelMutationType.ModelMutationType
import com.prisma.shared.models._

import scala.collection.immutable

sealed trait ApiMutaction

// DATABASE MUTACTIONS
sealed trait DatabaseMutaction extends ApiMutaction {
  def project: Project
  def allNestedMutactions: Vector[DatabaseMutaction] = Vector.empty
}

sealed trait TopLevelDatabaseMutaction extends DatabaseMutaction

sealed trait NestedDatabaseMutaction extends DatabaseMutaction {
  def relationField: RelationField
}

sealed trait FurtherNestedMutaction extends DatabaseMutaction {
  def nestedCreates: Vector[NestedCreateNode]
  def nestedUpdates: Vector[NestedUpdateNode]
  def nestedUpserts: Vector[NestedUpsertNode]
  def nestedDeletes: Vector[NestedDeleteNode]
  def nestedConnects: Vector[NestedConnect]
  def nestedDisconnects: Vector[NestedDisconnect]

  override def allNestedMutactions: Vector[NestedDatabaseMutaction] = {
    nestedCreates ++ nestedUpdates ++ nestedUpserts ++ nestedDeletes ++ nestedConnects ++ nestedDisconnects
  }
}

sealed trait FinalMutaction extends DatabaseMutaction

// CREATE
sealed trait CreateNode extends FurtherNestedMutaction {
  def model: Model
  def nonListArgs: PrismaArgs
  def listArgs: Vector[(String, ListGCValue)]

  override def nestedUpdates     = Vector.empty
  override def nestedUpserts     = Vector.empty
  override def nestedDeletes     = Vector.empty
  override def nestedDisconnects = Vector.empty
}
case class TopLevelCreateNode(
    project: Project,
    model: Model,
    nonListArgs: PrismaArgs,
    listArgs: Vector[(String, ListGCValue)],
    nestedCreates: Vector[NestedCreateNode],
    nestedConnects: Vector[NestedConnect]
) extends CreateNode
    with TopLevelDatabaseMutaction

case class NestedCreateNode(
    project: Project,
    relationField: RelationField,
    nonListArgs: PrismaArgs,
    listArgs: Vector[(String, ListGCValue)],
    nestedCreates: Vector[NestedCreateNode],
    nestedConnects: Vector[NestedConnect],
    topIsCreate: Boolean
) extends CreateNode
    with NestedDatabaseMutaction {
  override def model = relationField.relatedModel_!
}

// UPDATE
sealed trait UpdateNode extends FurtherNestedMutaction {
  def model: Model
  def nonListArgs: PrismaArgs
  def listArgs: Vector[(String, ListGCValue)]
}

case class TopLevelUpdateNode(
    project: Project,
    where: NodeSelector,
    nonListArgs: PrismaArgs,
    listArgs: Vector[(String, ListGCValue)],
    nestedCreates: Vector[NestedCreateNode],
    nestedUpdates: Vector[NestedUpdateNode],
    nestedUpserts: Vector[NestedUpsertNode],
    nestedDeletes: Vector[NestedDeleteNode],
    nestedConnects: Vector[NestedConnect],
    nestedDisconnects: Vector[NestedDisconnect]
) extends UpdateNode
    with TopLevelDatabaseMutaction {
  override def model = where.model
}

case class NestedUpdateNode(
    project: Project,
    relationField: RelationField,
    where: Option[NodeSelector],
    nonListArgs: PrismaArgs,
    listArgs: Vector[(String, ListGCValue)],
    nestedCreates: Vector[NestedCreateNode],
    nestedUpdates: Vector[NestedUpdateNode],
    nestedUpserts: Vector[NestedUpsertNode],
    nestedDeletes: Vector[NestedDeleteNode],
    nestedConnects: Vector[NestedConnect],
    nestedDisconnects: Vector[NestedDisconnect]
) extends UpdateNode
    with NestedDatabaseMutaction {
  override def model = relationField.relatedModel_!
}

// DELETE
sealed trait DeleteNode extends FinalMutaction {
  def model: Model
}
case class TopLevelDeleteNode(project: Project, where: NodeSelector, previousValues: PrismaNode) extends DeleteNode with TopLevelDatabaseMutaction {
  override def model = where.model
}
case class NestedDeleteNode(project: Project, relationField: RelationField, where: Option[NodeSelector]) extends DeleteNode with NestedDatabaseMutaction {
  override def model = relationField.relatedModel_!
}

// UPSERT
sealed trait UpsertNode

case class TopLevelUpsertNode(
    project: Project,
    where: NodeSelector,
    create: CreateNode,
    update: TopLevelUpdateNode
) extends UpsertNode
    with TopLevelDatabaseMutaction

case class NestedUpsertNode(
    project: Project,
    relationField: RelationField,
    where: Option[NodeSelector],
    create: NestedCreateNode,
    update: NestedUpdateNode
) extends UpsertNode
    with NestedDatabaseMutaction

// TOP LEVEL - MANY
case class ResetData(project: Project)                                              extends TopLevelDatabaseMutaction with FinalMutaction
case class DeleteNodes(project: Project, model: Model, whereFilter: Option[Filter]) extends TopLevelDatabaseMutaction with FinalMutaction
case class UpdateNodes(
    project: Project,
    model: Model,
    whereFilter: Option[Filter],
    updateArgs: PrismaArgs,
    listArgs: Vector[(String, ListGCValue)]
) extends TopLevelDatabaseMutaction
    with FinalMutaction

// NESTED

case class NestedConnect(project: Project, relationField: RelationField, where: NodeSelector, topIsCreate: Boolean)
    extends NestedDatabaseMutaction
    with FinalMutaction
case class NestedDisconnect(project: Project, relationField: RelationField, where: Option[NodeSelector]) extends NestedDatabaseMutaction with FinalMutaction

// IMPORT
case class ImportScalarLists(project: Project, field: ScalarField, values: Map[IdGCValue, ListGCValue]) extends TopLevelDatabaseMutaction with FinalMutaction
case class ImportRelations(project: Project, relation: Relation, args: Vector[(IdGCValue, IdGCValue)])  extends TopLevelDatabaseMutaction with FinalMutaction
case class ImportNodes(project: Project, model: Model, args: Vector[PrismaArgs])                        extends TopLevelDatabaseMutaction with FinalMutaction

// SIDE EFFECT MUTACTIONS
sealed trait SideEffectMutaction extends ApiMutaction

case class PublishSubscriptionEvent(
    project: Project,
    value: Map[String, Any],
    mutationName: String
) extends SideEffectMutaction

case class ExecuteServerSideSubscription(
    project: Project,
    model: Model,
    mutationType: ModelMutationType,
    function: ServerSideSubscriptionFunction,
    nodeId: IdGCValue,
    requestId: String,
    updatedFields: Option[List[String]] = None,
    previousValues: Option[PrismaNode] = None
) extends SideEffectMutaction
