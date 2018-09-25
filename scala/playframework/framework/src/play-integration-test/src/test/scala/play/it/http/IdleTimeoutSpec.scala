/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.it.http

import java.net.SocketException
import java.util.Properties

import akka.stream.scaladsl.Sink
import play.api.{ Configuration, Mode }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{ EssentialAction, Results }
import play.api.test._
import play.api.libs.streams.Accumulator
import play.core.server._
import play.it.{ AkkaHttpIntegrationSpecification, NettyIntegrationSpecification, ServerIntegrationSpecification }

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.Random

class NettyIdleTimeoutSpec extends IdleTimeoutSpec with NettyIntegrationSpecification

class AkkaIdleTimeoutSpec extends IdleTimeoutSpec with AkkaHttpIntegrationSpecification

trait IdleTimeoutSpec extends PlaySpecification with ServerIntegrationSpecification {

  val httpsPort = 9443

  def timeouts(httpTimeout: Duration, httpsTimeout: Duration): Map[String, String] = {

    def getTimeout(d: Duration) = d match {
      case Duration.Inf => "null"
      case Duration(t, u) => s"${u.toMillis(t)}ms"
    }

    Map(
      "play.server.http.idleTimeout" -> getTimeout(httpTimeout),
      "play.server.https.idleTimeout" -> getTimeout(httpsTimeout)
    )
  }

  "Play's idle timeout support" should {
    def withServerAndConfig[T](extraConfig: Map[String, AnyRef], httpsPort: Option[Int] = None)(action: EssentialAction)(block: Port => T) = {
      val port = testServerPort
      val props = new Properties(System.getProperties)
      val serverConfig = ServerConfig(port = Some(port), sslPort = httpsPort, mode = Mode.Test, properties = props)

      val configuration = Configuration.load(play.api.Environment.simple(), extraConfig)

      running(play.api.test.TestServer(
        config = serverConfig.copy(configuration = configuration),
        application = new GuiceApplicationBuilder()
          .routes({
            case _ => action
          }).build(),
        serverProvider = Some(integrationServerProvider))) {
        block(port)
      }
    }

    def withServer[T](httpTimeout: Duration, httpsPort: Option[Int] = None, httpsTimeout: Duration = Duration.Inf)(action: EssentialAction)(block: Port => T) = {
      withServerAndConfig(extraConfig = timeouts(httpTimeout, httpsTimeout), httpsPort)(action)(block)
    }

    def doRequests(port: Int, trickle: Long, secure: Boolean = false) = {
      val body = new String(Random.alphanumeric.take(50 * 1024).toArray)
      val responses = BasicHttpClient.makeRequests(port, secure = secure, trickleFeed = Some(trickle))(
        BasicRequest("POST", "/", "HTTP/1.1", Map("Content-Length" -> body.length.toString), body),
        // Second request ensures that Play switches back to its normal handler
        BasicRequest("GET", "/", "HTTP/1.1", Map(), "")
      )
      responses
    }

    "support null as an infinite timeout" in withServerAndConfig(Map(
      "play.server.http.idleTimeout" -> null,
      "play.server.https.idleTimeout" -> null
    ))(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      // We are interested to know that the server started correctly with "null"
      // configurations. So there is no need to wait for a longer time.
      val responses = doRequests(port, trickle = 200L)
      responses.length must_== 2
      responses(0).status must_== 200
      responses(1).status must_== 200
    }.skipOnSlowCIServer

    "support 'infinite' as an infinite timeout" in withServerAndConfig(Map(
      "play.server.http.idleTimeout" -> "infinite",
      "play.server.https.idleTimeout" -> "infinite"
    ))(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      // We are interested to know that the server started correctly with "infinite"
      // configurations. So there is no need to wait for a longer time.
      val responses = doRequests(port, trickle = 200L)
      responses.length must_== 2
      responses(0).status must_== 200
      responses(1).status must_== 200
    }.skipOnSlowCIServer

    "support sub-second timeouts" in withServer(300.millis)(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      doRequests(port, trickle = 400L) must throwA[SocketException]
    }.skipOnSlowCIServer

    "support a separate timeout for https" in withServer(1.second, httpsPort = Some(httpsPort), httpsTimeout = 400.millis)(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      val responses = doRequests(port, trickle = 200L)
      responses.length must_== 2
      responses(0).status must_== 200
      responses(1).status must_== 200

      doRequests(httpsPort, trickle = 600L, secure = true) must throwA[SocketException]
    }.skipOnSlowCIServer

    "support multi-second timeouts" in withServer(1500.millis)(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      doRequests(port, trickle = 1600L) must throwA[SocketException]
    }.skipOnSlowCIServer

    "not timeout for slow requests with a sub-second timeout" in withServer(700.millis)(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      val responses = doRequests(port, trickle = 400L)
      responses.length must_== 2
      responses(0).status must_== 200
      responses(1).status must_== 200
    }.skipOnSlowCIServer

    "not timeout for slow requests with a multi-second timeout" in withServer(1500.millis)(EssentialAction { req =>
      Accumulator(Sink.ignore).map(_ => Results.Ok)
    }) { port =>
      val responses = doRequests(port, trickle = 1000L)
      responses.length must_== 2
      responses(0).status must_== 200
      responses(1).status must_== 200
    }.skipOnSlowCIServer
  }

}
