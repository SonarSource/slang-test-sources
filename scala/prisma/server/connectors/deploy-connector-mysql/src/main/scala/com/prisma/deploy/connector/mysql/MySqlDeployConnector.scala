package com.prisma.deploy.connector.mysql

import com.prisma.config.DatabaseConfig
import com.prisma.deploy.connector._
import com.prisma.deploy.connector.mysql.database.{MySqlDeployDatabaseMutationBuilder, MysqlInternalDatabaseSchema, TelemetryTable}
import com.prisma.deploy.connector.mysql.impls._
import com.prisma.shared.models.{Project, ProjectIdEncoder}
import org.joda.time.DateTime
import slick.dbio.Effect.Read
import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.meta.MTable

import scala.concurrent.{ExecutionContext, Future}

case class MySqlDeployConnector(config: DatabaseConfig)(implicit ec: ExecutionContext) extends DeployConnector {
  override def isActive         = true
  lazy val internalDatabaseDefs = MysqlInternalDatabaseDefs(config)
  lazy val setupDatabase        = internalDatabaseDefs.setupDatabase
  lazy val managementDatabase   = internalDatabaseDefs.managementDatabase
  lazy val projectDatabase      = internalDatabaseDefs.managementDatabase

  override val projectPersistence: ProjectPersistence           = MysqlProjectPersistence(managementDatabase)
  override val migrationPersistence: MigrationPersistence       = MysqlMigrationPersistence(managementDatabase)
  override val deployMutactionExecutor: DeployMutactionExecutor = MySqlDeployMutactionExectutor(projectDatabase)

  override def createProjectDatabase(id: String): Future[Unit] = {
    val action = MySqlDeployDatabaseMutationBuilder.createClientDatabaseForProject(projectId = id)
    projectDatabase.run(action)
  }

  override def deleteProjectDatabase(id: String): Future[Unit] = {
    val action = MySqlDeployDatabaseMutationBuilder.deleteProjectDatabase(projectId = id).map(_ => ())
    projectDatabase.run(action)
  }

  override def getAllDatabaseSizes(): Future[Vector[DatabaseSize]] = {
    val action = {
      val query = sql"""SELECT table_schema, sum( data_length + index_length) / 1024 / 1024 FROM information_schema.TABLES GROUP BY table_schema"""
      query.as[(String, Double)].map { tuples =>
        tuples.map { tuple =>
          DatabaseSize(tuple._1, tuple._2)
        }
      }
    }

    projectDatabase.run(action)
  }

  override def clientDBQueries(project: Project): ClientDbQueries      = MySqlClientDbQueries(project, projectDatabase)
  override def getOrCreateTelemetryInfo(): Future[TelemetryInfo]       = managementDatabase.run(TelemetryTable.getOrCreateInfo())
  override def updateTelemetryInfo(lastPinged: DateTime): Future[Unit] = managementDatabase.run(TelemetryTable.updateInfo(lastPinged)).map(_ => ())
  override def projectIdEncoder: ProjectIdEncoder                      = ProjectIdEncoder('@')
  override def cloudSecretPersistence                                  = CloudSecretPersistenceImpl(managementDatabase)

  override def initialize(): Future[Unit] = {
    setupDatabase
      .run(MysqlInternalDatabaseSchema.createSchemaActions(internalDatabaseDefs.managementSchemaName, recreate = false))
      .flatMap(_ => internalDatabaseDefs.setupDatabase.shutdown)
  }

  override def reset(): Future[Unit] = truncateTablesInDatabase(managementDatabase)

  override def shutdown() = {
    for {
      _ <- setupDatabase.shutdown
      _ <- managementDatabase.shutdown
    } yield ()
  }

  override def databaseIntrospectionInferrer(projectId: String) = EmptyDatabaseIntrospectionInferrer

  protected def truncateTablesInDatabase(database: Database)(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      schemas <- database.run(getTables(internalDatabaseDefs.managementSchemaName))
      _       <- database.run(dangerouslyTruncateTables(schemas))
    } yield ()
  }

  private def getTables(database: String)(implicit ec: ExecutionContext): DBIOAction[Vector[String], NoStream, Read] = {
    for {
      metaTables <- MTable.getTables(cat = Some(database), schemaPattern = None, namePattern = None, types = None)
    } yield metaTables.map(table => table.name.name)
  }

  private def dangerouslyTruncateTables(tableNames: Vector[String]): DBIOAction[Unit, NoStream, Effect] = {
    DBIO.seq(
      List(sqlu"""SET FOREIGN_KEY_CHECKS=0""") ++
        tableNames.map(name => sqlu"TRUNCATE TABLE `#$name`") ++
        List(sqlu"""SET FOREIGN_KEY_CHECKS=1"""): _*
    )
  }
}
