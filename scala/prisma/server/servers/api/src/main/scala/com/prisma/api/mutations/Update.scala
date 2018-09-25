package com.prisma.api.mutations

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.prisma.api.ApiDependencies
import com.prisma.api.connector._
import com.prisma.api.mutactions.DatabaseMutactions
import com.prisma.shared.models.{Model, Project}
import com.prisma.util.coolArgs.CoolArgs
import sangria.schema

import scala.concurrent.Future

case class Update(
    model: Model,
    project: Project,
    args: schema.Args,
    selectedFields: SelectedFields,
    dataResolver: DataResolver
)(implicit apiDependencies: ApiDependencies)
    extends SingleItemClientMutation {

  implicit val system: ActorSystem             = apiDependencies.system
  implicit val materializer: ActorMaterializer = apiDependencies.materializer

  val coolArgs = CoolArgs.fromSchemaArgs(args.raw)
  val where    = CoolArgs(args.raw).extractNodeSelectorFromWhereField(model)

  lazy val prismaNode: Future[Option[PrismaNode]] = dataResolver.getNodeByWhere(where, selectedFields)

  def prepareMutactions(): Future[TopLevelDatabaseMutaction] = Future.successful {
    DatabaseMutactions(project).getMutactionsForUpdate(model, where, coolArgs)
  }

  override def getReturnValue(results: MutactionResults): Future[ReturnValueResult] = {
    val udpateResult = results.databaseResult.asInstanceOf[UpdateNodeResult]
    returnValueByUnique(NodeSelector.forIdGCValue(model, udpateResult.id), selectedFields)
  }

}
