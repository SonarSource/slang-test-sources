package com.prisma.deploy.specutils

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.prisma.ConnectorAwareTest
import com.prisma.deploy.connector.postgres.PostgresDeployConnector
import com.prisma.shared.models.{Migration, Project, ProjectId}
import com.prisma.utils.await.AwaitUtils
import com.prisma.utils.json.PlayJsonExtensions
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import play.api.libs.json.JsString

import scala.collection.mutable.ArrayBuffer

trait DeploySpecBase extends ConnectorAwareTest with BeforeAndAfterEach with BeforeAndAfterAll with AwaitUtils with PlayJsonExtensions { self: Suite =>

  implicit lazy val system                                   = ActorSystem()
  implicit lazy val materializer                             = ActorMaterializer()
  implicit lazy val testDependencies: TestDeployDependencies = TestDeployDependencies()
  implicit lazy val implicitSuite                            = self
  implicit lazy val deployConnector                          = testDependencies.deployConnector

  override def prismaConfig = testDependencies.config

  val server            = DeployTestServer()
  val internalDB        = testDependencies.deployConnector
  val projectsToCleanUp = new ArrayBuffer[String]

  val basicTypesGql =
    """
      |type TestModel {
      |  id: ID! @unique
      |}
    """.stripMargin

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    deployConnector.initialize().await()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
//    projectsToCleanUp.foreach(internalDB.deleteProjectDatabase)
    deployConnector.shutdown().await()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
//    projectsToCleanUp.foreach(internalDB.deleteProjectDatabase)
//    projectsToCleanUp.clear()
    deployConnector.reset().await
  }

  def setupProject(
      schema: String,
      stage: String = "default",
      secrets: Vector[String] = Vector.empty
  )(implicit suite: Suite): (Project, Migration) = {
    val name      = suite.getClass.getSimpleName
    val idAsStrig = testDependencies.projectIdEncoder.toEncodedString(name, stage)
    internalDB.deleteProjectDatabase(idAsStrig).await()
    server.addProject(name, stage)
    server.deploySchema(name, stage, schema.stripMargin, secrets)
  }

  def formatSchema(schema: String): String = JsString(schema).toString()
  def escapeString(str: String): String    = JsString(str).toString()
}

trait ActiveDeploySpecBase extends DeploySpecBase { self: Suite =>

  override def runSuiteOnlyForActiveConnectors = true
}

trait PassiveDeploySpecBase extends DeploySpecBase { self: Suite =>
  override def runSuiteOnlyForPassiveConnectors = true

  val projectName  = this.getClass.getSimpleName
  val projectStage = "default"
  val projectId    = s"$projectName$$$projectStage"

  def setupProjectDatabaseForProject(sql: String)(implicit suite: Suite): Unit = {
//    setupProjectDatabaseForProject("passive_test", projectName, projectStage, sql)
    setupProjectDatabaseForProject(projectId, projectName, projectStage, sql)
  }

  private def setupProjectDatabaseForProject(schemaName: String, name: String, stage: String, sql: String): Unit = {
    val connector = deployConnector.asInstanceOf[PostgresDeployConnector]
    val session   = connector.managementDatabase.createSession()
    val statement = session.createStatement()
    statement.execute(s"drop schema if exists $schemaName cascade;")

    server.addProject(name, stage)
    statement.execute(s"create schema if not exists $schemaName;")

    statement.execute(s"SET search_path TO $schemaName;")
    statement.execute(sql)
    session.close()
  }
}
