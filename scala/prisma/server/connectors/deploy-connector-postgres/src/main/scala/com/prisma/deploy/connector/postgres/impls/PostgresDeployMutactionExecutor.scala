package com.prisma.deploy.connector.postgres.impls

import com.prisma.deploy.connector.postgres.impls.mutactions.AnyMutactionInterpreterImpl
import com.prisma.deploy.connector.{DeployMutaction, DeployMutactionExecutor}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{ExecutionContext, Future}

case class PostgresDeployMutactionExecutor(
    database: Database
)(implicit ec: ExecutionContext)
    extends DeployMutactionExecutor {

  override def execute(mutaction: DeployMutaction): Future[Unit] = {
    val action = AnyMutactionInterpreterImpl.execute(mutaction)
    database.run(action).map(_ => ())
  }

  override def rollback(mutaction: DeployMutaction): Future[Unit] = {
    val action = AnyMutactionInterpreterImpl.rollback(mutaction)
    database.run(action).map(_ => ())
  }

}
