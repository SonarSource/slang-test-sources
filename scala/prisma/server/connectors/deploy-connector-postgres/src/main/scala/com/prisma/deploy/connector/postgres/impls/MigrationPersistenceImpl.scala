package com.prisma.deploy.connector.postgres.impls

import com.prisma.deploy.connector.MigrationPersistence
import com.prisma.deploy.connector.postgres.database.MigrationTable
import com.prisma.shared.models.MigrationStatus.MigrationStatus
import com.prisma.shared.models.{Migration, MigrationId}
import com.prisma.utils.future.FutureUtils.FutureOpt
import org.joda.time.DateTime
import play.api.libs.json.Json
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile.backend.DatabaseDef
import slick.lifted.TableQuery

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class MigrationPersistenceImpl(
    internalDatabase: DatabaseDef
)(implicit ec: ExecutionContext)
    extends MigrationPersistence {

  val table = TableQuery[MigrationTable]

  def lock(): Future[Int] = {
    internalDatabase.run(sql"SELECT pg_advisory_lock(1000);".as[String].head.withPinnedSession).transformWith {
      case Success(_)   => Future.successful(1)
      case Failure(err) => Future.failed(err)
    }
  }

  override def byId(migrationId: MigrationId): Future[Option[Migration]] = {
    val baseQuery = for {
      migration <- table
      if migration.projectId === migrationId.projectId
      if migration.revision === migrationId.revision
    } yield migration

    internalDatabase.run(baseQuery.result.headOption).map(_.map(DbToModelMapper.convert))
  }

  override def loadAll(projectId: String): Future[Seq[Migration]] = {
    val baseQuery = for {
      migration <- table
      if migration.projectId === projectId
    } yield migration

    val query = baseQuery.sortBy(_.revision.desc)
    internalDatabase.run(query.result).map(_.map(DbToModelMapper.convert))
  }

  override def create(migration: Migration): Future[Migration] = {
    for {
      lastRevision       <- internalDatabase.run(MigrationTable.lastRevision(migration.projectId))
      dbMigration        = ModelToDbMapper.convert(migration)
      withRevisionBumped = dbMigration.copy(revision = lastRevision.getOrElse(0) + 1)
      addMigration       = table += withRevisionBumped
      _                  <- internalDatabase.run(addMigration)
    } yield migration.copy(revision = withRevisionBumped.revision)
  }

  override def updateMigrationStatus(id: MigrationId, status: MigrationStatus): Future[Unit] = {
    internalDatabase.run(MigrationTable.updateMigrationStatus(id.projectId, id.revision, status)).map(_ => ())
  }

  override def updateMigrationErrors(id: MigrationId, errors: Vector[String]): Future[Unit] = {
    val errorsJson = Json.toJson(errors)
    internalDatabase.run(MigrationTable.updateMigrationErrors(id.projectId, id.revision, errorsJson)).map(_ => ())
  }

  override def updateMigrationApplied(id: MigrationId, applied: Int): Future[Unit] = {
    internalDatabase.run(MigrationTable.updateMigrationApplied(id.projectId, id.revision, applied)).map(_ => ())
  }

  override def updateMigrationRolledBack(id: MigrationId, rolledBack: Int): Future[Unit] = {
    internalDatabase.run(MigrationTable.updateMigrationRolledBack(id.projectId, id.revision, rolledBack)).map(_ => ())
  }

  override def updateStartedAt(id: MigrationId, startedAt: DateTime): Future[Unit] = {
    internalDatabase.run(MigrationTable.updateStartedAt(id.projectId, id.revision, startedAt)).map(_ => ())
  }

  override def updateFinishedAt(id: MigrationId, finishedAt: DateTime): Future[Unit] = {
    internalDatabase.run(MigrationTable.updateFinishedAt(id.projectId, id.revision, finishedAt)).map(_ => ())
  }

  override def getLastMigration(projectId: String): Future[Option[Migration]] = {
    FutureOpt(internalDatabase.run(MigrationTable.lastSuccessfulMigration(projectId))).map(DbToModelMapper.convert).future
  }

  override def getNextMigration(projectId: String): Future[Option[Migration]] = {
    FutureOpt(internalDatabase.run(MigrationTable.nextOpenMigration(projectId))).map(DbToModelMapper.convert).future
  }

  override def loadDistinctUnmigratedProjectIds(): Future[Seq[String]] = {
    internalDatabase.run(MigrationTable.distinctUnmigratedProjectIds())
  }
}
