package com.prisma.deploy.schema.mutations

import com.prisma.deploy.DeployDependencies
import com.prisma.deploy.connector.{DeployConnector, ProjectPersistence}
import com.prisma.deploy.schema.InvalidProjectId
import com.prisma.messagebus.PubSubPublisher
import com.prisma.messagebus.pubsub.Only
import com.prisma.shared.models._

import scala.concurrent.{ExecutionContext, Future}

case class DeleteProjectMutation(
    args: DeleteProjectInput,
    projectPersistence: ProjectPersistence,
    invalidationPubSub: PubSubPublisher[String],
    deployConnector: DeployConnector
)(
    implicit ec: ExecutionContext,
    dependencies: DeployDependencies
) extends Mutation[DeleteProjectMutationPayload] {

  val projectIdEncoder = dependencies.projectIdEncoder
  val projectId        = projectIdEncoder.toEncodedString(name = args.name, stage = args.stage)

  override def execute: Future[MutationResult[DeleteProjectMutationPayload]] = {
    for {
      projectOpt <- projectPersistence.load(projectId)
      project    = validate(projectOpt)
      _          <- projectPersistence.delete(projectId)
//      stmt       <- DeleteClientDatabaseForProject(projectId).execute
//      _          <- clientDb.run(stmt.sqlAction)
      _ <- deployConnector.deleteProjectDatabase(projectId)
      _ = invalidationPubSub.publish(Only(projectId), projectId)
    } yield MutationSuccess(DeleteProjectMutationPayload(args.clientMutationId, project))
  }

  private def validate(project: Option[Project]): Project = {
    project match {
      case None    => throw InvalidProjectId(projectIdEncoder.fromEncodedString(projectId))
      case Some(p) => p
    }
  }
}

case class DeleteProjectMutationPayload(
    clientMutationId: Option[String],
    project: Project
) extends sangria.relay.Mutation

case class DeleteProjectInput(
    clientMutationId: Option[String],
    name: String,
    stage: String
) extends sangria.relay.Mutation
