package com.prisma.subscriptions.specs

import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.{ScalatestRouteTest, TestFrameworkInterface, WSProbe}
import akka.stream.ActorMaterializer
import com.prisma.ConnectorAwareTest
import com.prisma.akkautil.http.ServerExecutor
import com.prisma.api.ApiTestDatabase
import com.prisma.shared.models.{Project, ProjectId, ProjectWithClientId}
import com.prisma.subscriptions._
import com.prisma.utils.await.AwaitUtils
import com.prisma.websocket.WebsocketServer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

trait SubscriptionSpecBase
    extends ConnectorAwareTest
    with AwaitUtils
    with TestFrameworkInterface
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalatestRouteTest {

  this: Suite =>
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val dependencies                 = new TestSubscriptionDependencies()
  implicit lazy val implicitSuite           = this
  implicit lazy val deployConnector         = dependencies.deployConnector
  val testDatabase                          = ApiTestDatabase()
  implicit val actorSytem                   = ActorSystem("test")
  implicit val mat                          = ActorMaterializer()
  val sssEventsTestKit                      = dependencies.sssEventsTestKit
  val invalidationTestKit                   = dependencies.invalidationTestKit
  val requestsTestKit                       = dependencies.requestsQueueTestKit
  val responsesTestKit                      = dependencies.responsePubSubTestKit
  val projectIdEncoder                      = dependencies.projectIdEncoder

  override def prismaConfig = dependencies.config

  val wsServer            = WebsocketServer(dependencies)
  val simpleSubServer     = SimpleSubscriptionsServer()
  val subscriptionServers = ServerExecutor(port = 8085, wsServer, simpleSubServer)

  Await.result(subscriptionServers.start, 15.seconds)

  var caseNumber = 1

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    deployConnector.initialize().await()
//    testDatabase.beforeAllPublic()
  }

  override def beforeEach(): Unit = {
    println((">" * 25) + s" starting test $caseNumber")
    caseNumber += 1
    super.beforeEach()
//    testDatabase.beforeEach()
    sssEventsTestKit.reset
    invalidationTestKit.reset
    responsesTestKit.reset
    requestsTestKit.reset
  }

  override def afterAll(): Unit = {
    println("finished spec " + (">" * 50))
    super.afterAll()
    subscriptionServers.stopBlocking()
//    testDatabase.afterAll()
  }

  def sleep(millis: Long = 2000): Unit = {
    Thread.sleep(millis)
  }

  def testInitializedWebsocket(project: Project)(checkFn: WSProbe => Unit): Unit = {
    testWebsocket(project) { wsClient =>
      wsClient.sendMessage(connectionInit)
      wsClient.expectMessage(connectionAck)
      checkFn(wsClient)
    }
  }

  def testWebsocket(project: Project)(checkFn: WSProbe => Unit): Unit = {
    val wsClient = WSProbe()
    import com.prisma.shared.models.ProjectJsonFormatter._
    import com.prisma.stub.Import._

    val projectWithClientId   = ProjectWithClientId(project, "clientId")
    val dummyStage            = "test"
    val projectIdInFetcherUrl = projectIdEncoder.toEncodedString(project.id, dummyStage)
    val stubs = List(
      com.prisma.stub.Import.Request("GET", s"/${dependencies.projectFetcherPath}/$projectIdInFetcherUrl").stub(200, Json.toJson(projectWithClientId).toString)
    )
    withStubServer(stubs, port = dependencies.projectFetcherPort) {
      WS(s"/${project.id}/$dummyStage", wsClient.flow, Seq(wsServer.v7ProtocolName)) ~> wsServer.routes ~> check {
        checkFn(wsClient)
      }
    }
  }

  /**
    * MESSAGES FOR PROTOCOL VERSION 0.7
    */
  val cantBeParsedError      = """{"id":"","payload":{"message":"The message can't be parsed"},"type":"error"}"""
  val connectionAck          = """{"type":"connection_ack"}"""
  val connectionInit: String = connectionInit(None)

  def connectionInit(token: String): String = connectionInit(Some(token))

  def connectionInit(token: Option[String]): String = token match {
    case Some(token) => s"""{"type":"connection_init","payload":{"Authorization": "Bearer $token"}}"""
    case None        => s"""{"type":"connection_init","payload":{}}"""
  }

  def startMessage(id: String, query: String, variables: JsObject = Json.obj()): String = {
    startMessage(id, query, variables = variables, operationName = None)
  }

  def startMessage(id: String, query: String, operationName: String): String = {
    startMessage(id, query, Json.obj(), Some(operationName))
  }

  def startMessage(id: String, query: String, variables: JsValue, operationName: Option[String]): String = {
    Json
      .obj(
        "id"   -> id,
        "type" -> "start",
        "payload" -> Json.obj(
          "variables"     -> variables,
          "operationName" -> operationName,
          "query"         -> query
        )
      )
      .toString
  }

  def startMessage(id: Int, query: String, variables: JsValue, operationName: Option[String]): String = {
    Json
      .obj(
        "id"   -> id,
        "type" -> "start",
        "payload" -> Json.obj(
          "variables"     -> variables,
          "operationName" -> operationName,
          "query"         -> query
        )
      )
      .toString
  }

  def stopMessage(id: String): String = s"""{"type":"stop","id":"$id"}"""
  def stopMessage(id: Int): String    = s"""{"type":"stop","id":"$id"}"""

  def dataMessage(id: String, payload: String): String = {
    val payloadAsJson = Json.parse(payload)
    Json
      .obj(
        "id" -> id,
        "payload" -> Json.obj(
          "data" -> payloadAsJson
        ),
        "type" -> "data"
      )
      .toString
  }

  def dataMessage(id: Int, payload: String): String = {
    val payloadAsJson = Json.parse(payload)
    Json
      .obj(
        "id" -> id,
        "payload" -> Json.obj(
          "data" -> payloadAsJson
        ),
        "type" -> "data"
      )
      .toString
  }

  def errorMessage(id: String, message: String): String = {
    Json
      .obj(
        "id" -> id,
        "payload" -> Json.obj(
          "message" -> message
        ),
        "type" -> "error"
      )
      .toString
  }

}
