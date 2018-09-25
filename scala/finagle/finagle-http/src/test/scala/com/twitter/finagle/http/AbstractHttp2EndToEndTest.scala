package com.twitter.finagle.http

import java.net.InetSocketAddress

// Adds some tests that are specific to the HTTP/2 transports
abstract class AbstractHttp2EndToEndTest extends AbstractEndToEndTest {

  test("client closes properly when closed") {

    val server = serverImpl()
      .serve("localhost:*", initService)

    val addr = server.boundAddress.asInstanceOf[InetSocketAddress]
    val client = clientImpl()
      .newService(s"${addr.getHostName}:${addr.getPort}", "client")

    initClient(client)

    await(client(Request(Method.Get, "/")))
    await(client.close())

    // For some reason the upgrading client closes a bunch of times which makes
    // the counter kind of meaningless quantitatively but we need to close at
    // least one time.
    assert(statsRecv.counters(Seq("client", "closes")) > 0)

    await(server.close())
  }
}
