package com.prisma.deploy.connector.postgres.impls

import com.prisma.deploy.connector.ClientDbQueries
import com.prisma.deploy.connector.postgres.database.PostgresDeployDatabaseQueryBuilder
import com.prisma.shared.models.RelationSide.RelationSide
import com.prisma.shared.models._
import slick.dbio.Effect.Read
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.SQLActionBuilder
import slick.sql.SqlStreamingAction

import scala.concurrent.{ExecutionContext, Future}

case class PostgresClientDbQueries(project: Project, clientDatabase: Database)(implicit ec: ExecutionContext) extends ClientDbQueries {

  def existsByModel(modelName: String): Future[Boolean] = {
    val model = project.schema.getModelByName_!(modelName)
    val query = PostgresDeployDatabaseQueryBuilder.existsByModel(project.id, model)
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  def existsDuplicateByRelationAndSide(relationId: String, relationSide: RelationSide): Future[Boolean] = {
    val query = PostgresDeployDatabaseQueryBuilder.existsDuplicateByRelationAndSide(project.id, relationId, relationSide)
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  def existsByRelation(relationId: String): Future[Boolean] = {
    val query = PostgresDeployDatabaseQueryBuilder.existsByRelation(project.id, relationId)
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  def existsNullByModelAndField(model: Model, field: Field): Future[Boolean] = {
    val query = field match {
      case f: ScalarField   => PostgresDeployDatabaseQueryBuilder.existsNullByModelAndScalarField(project.id, model, f.dbName)
      case f: RelationField => PostgresDeployDatabaseQueryBuilder.existsNullByModelAndRelationField(project.id, model, f)
    }
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  def existsDuplicateValueByModelAndField(model: Model, field: ScalarField): Future[Boolean] = {
    val query = PostgresDeployDatabaseQueryBuilder.existsDuplicateValueByModelAndField(project.id, model, field.dbName)
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  override def enumValueIsInUse(models: Vector[Model], enumName: String, value: String): Future[Boolean] = {
    val query = PostgresDeployDatabaseQueryBuilder.enumValueIsInUse(project.id, models, enumName, value)
    clientDatabase.run(readOnlyBoolean(query)).map(_.head).recover { case _: java.sql.SQLSyntaxErrorException => false }
  }

  private def readOnlyBoolean(query: SQLActionBuilder): SqlStreamingAction[Vector[Boolean], Boolean, Read] = query.as[Boolean]
}
