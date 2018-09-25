package com.twitter.finagle.builder

import com.twitter.conversions.time._
import com.twitter.finagle.ChannelClosedException
import com.twitter.finagle.Service
import com.twitter.finagle.client.utils.StringClient
import com.twitter.finagle.server.utils.StringServer
import com.twitter.util.{Await, Future}
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class ServerChannelConfigurationTest extends FunSuite {

  val identityService = Service.mk[String, String] { req =>
    Future.value(req)
  }

  test("close connection after max life time duration") {
    val lifeTime = 100.millis
    val address = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server = ServerBuilder()
      .stack(StringServer.Server().withSession.maxLifeTime(lifeTime))
      .bindTo(address)
      .name("FinagleServer")
      .build(identityService)

    val client: Service[String, String] = ClientBuilder()
      .stack(StringClient.Client(appendDelimeter = false))
      .daemon(true) // don't create an exit guard
      .hosts(server.boundAddress.asInstanceOf[InetSocketAddress])
      .hostConnectionLimit(1)
      .build()

    // Issue a request which is NOT newline-delimited. Server should close connection
    // after waiting `lifeTime` for a new line
    intercept[ChannelClosedException] { Await.result(client("123"), lifeTime * 3) }
    server.close()
  }

  test("close connection after max idle time duration") {
    val idleTime = 100.millis
    val address = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server = ServerBuilder()
      .stack(StringServer.Server().withSession.maxIdleTime(idleTime))
      .bindTo(address)
      .name("FinagleServer")
      .build(identityService)

    val client: Service[String, String] = ClientBuilder()
      .stack(StringClient.Client(appendDelimeter = false))
      .daemon(true) // don't create an exit guard
      .hosts(server.boundAddress.asInstanceOf[InetSocketAddress])
      .hostConnectionLimit(1)
      .build()

    // Issue a request which is NOT newline-delimited. Server should close connection
    // after waiting `idleTime` for a new line
    intercept[ChannelClosedException] { Await.result(client("123"), idleTime * 3) }
    server.close()
  }
}
