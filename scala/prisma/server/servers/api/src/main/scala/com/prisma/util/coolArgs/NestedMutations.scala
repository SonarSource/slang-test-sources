package com.prisma.util.coolArgs

import com.prisma.api.connector._

case class NestedMutations(
    creates: Vector[CreateOne],
    updates: Vector[UpdateOne],
    upserts: Vector[UpsertOne],
    deletes: Vector[DeleteOne],
    connects: Vector[ConnectByWhere],
    disconnects: Vector[DisconnectOne]
)

object NestedMutations {
  def empty = NestedMutations(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
}

sealed trait NestedMutation
sealed trait NestedWhere { def where: NodeSelector }
case class CreateOne(data: CoolArgs) extends NestedMutation

case class ConnectByWhere(where: NodeSelector) extends NestedMutation with NestedWhere

sealed trait UpdateOne                                        extends NestedMutation { def data: CoolArgs }
case class UpdateByRelation(data: CoolArgs)                   extends UpdateOne
case class UpdateByWhere(where: NodeSelector, data: CoolArgs) extends UpdateOne with NestedWhere

sealed trait UpsertOne                                                            extends NestedMutation { def create: CoolArgs; def update: CoolArgs }
case class UpsertByRelation(create: CoolArgs, update: CoolArgs)                   extends UpsertOne
case class UpsertByWhere(where: NodeSelector, create: CoolArgs, update: CoolArgs) extends UpsertOne with NestedWhere

sealed trait DeleteOne                        extends NestedMutation
case class DeleteByRelation(boolean: Boolean) extends DeleteOne
case class DeleteByWhere(where: NodeSelector) extends DeleteOne with NestedWhere

sealed trait DisconnectOne                        extends NestedMutation
case class DisconnectByRelation(boolean: Boolean) extends DisconnectOne
case class DisconnectByWhere(where: NodeSelector) extends DisconnectOne with NestedWhere

case class ScalarListSet(values: Vector[Any])
