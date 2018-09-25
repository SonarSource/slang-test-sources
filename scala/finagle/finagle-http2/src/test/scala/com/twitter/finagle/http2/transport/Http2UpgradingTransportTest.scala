package com.twitter.finagle.http2.transport

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.time._
import com.twitter.finagle.{Stack, Status}
import com.twitter.finagle.http2.RefTransport
import com.twitter.finagle.http2.SerialExecutor
import com.twitter.finagle.http2.transport.Http2UpgradingTransport.UpgradeIgnoredException
import com.twitter.finagle.netty4.transport.HasExecutor
import com.twitter.finagle.transport.{LegacyContext, QueueTransport, TransportContext}
import com.twitter.util.{Await, Future, Promise}
import java.util.concurrent.Executor
import io.netty.handler.codec.http._
import org.scalatest.FunSuite

class Http2UpgradingTransportTest extends FunSuite {
  class Ctx {
    val (writeq, readq) = (new AsyncQueue[Any](), new AsyncQueue[Any]())
    val transport = new QueueTransport[Any, Any](writeq, readq) {
      override val context: TransportContext = new LegacyContext(this) with HasExecutor {
        private[finagle] override val executor: Executor = new SerialExecutor
      }
    }
    val ref = new RefTransport(transport)
    val p = Promise[Option[StreamTransportFactory]]()

    def http1Status: Status = Status.Open

    val upgradingTransport = new Http2UpgradingTransport(
      transport,
      ref,
      p,
      Stack.Params.empty,
      () => http1Status
    )
  }

  val fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
  val fullResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

  def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  test("Http2UpgradingTransport upgrades properly") {
    val ctx = new Ctx
    import ctx._

    val writeF = upgradingTransport.write(fullRequest)
    assert(await(writeq.poll) == fullRequest)
    val readF = upgradingTransport.read()
    assert(!readF.isDefined)
    assert(readq.offer(UpgradeRequestHandler.UpgradeSuccessful))
    assert(await(p).nonEmpty)
    assert(readq.offer(Http2ClientDowngrader.Message(fullResponse, 1)))
    assert(await(readF) == fullResponse)
  }

  test("Http2UpgradingTransport can reject an upgrade") {
    val ctx = new Ctx
    import ctx._

    val writeF = upgradingTransport.write(fullRequest)
    assert(await(writeq.poll) == fullRequest)
    val readF = upgradingTransport.read()
    assert(!readF.isDefined)
    assert(readq.offer(UpgradeRequestHandler.UpgradeRejected))
    assert(await(p).isEmpty)
    assert(readq.offer(fullResponse))
    assert(await(readF) == fullResponse)
  }

    test("Http2UpgradingTransport honors aborted upgrade dispatches") {
      val ctx = new Ctx
      import ctx._

      val partialRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "twitter.com")
      val partialF = upgradingTransport.write(partialRequest)
      assert(await(writeq.poll) == partialRequest)

      val readF = upgradingTransport.read()
      assert(!readF.isDefined)
      assert(readq.offer(UpgradeRequestHandler.UpgradeAborted))
      intercept[UpgradeIgnoredException.type] {
        await(p)
      }

      assert(readq.offer(fullResponse))
      assert(await(readF) == fullResponse)
    }

  test("Http2UpgradingTransport honors the status if the upgrade is rejected") {
    var status: Status = Status.Open

    val ctx = new Ctx {
      override def http1Status: Status = status
    }
    import ctx._

    assert(upgradingTransport.status == Status.Open)
    status = Status.Closed
    // Haven't finished upgrade yet
    assert(upgradingTransport.status == Status.Open)

    // Reject upgrade rejected
    val writeF = upgradingTransport.write(fullRequest)
    assert(await(writeq.poll) == fullRequest)
    val readF = upgradingTransport.read()
    assert(!readF.isDefined)
    assert(readq.offer(UpgradeRequestHandler.UpgradeRejected))
    assert(await(p).isEmpty)
    assert(readq.offer(fullResponse))
    assert(await(readF) == fullResponse)

    // When the upgrade is rejected we revert to the parent behavior
    assert(upgradingTransport.status == Status.Closed)
  }

  test("Http2UpgradingTransport honors the status if the upgrade is ignored") {
    var status: Status = Status.Open

    val ctx = new Ctx {
      override def http1Status: Status = status
    }
    import ctx._

    assert(upgradingTransport.status == Status.Open)
    status = Status.Closed
    // Haven't finished upgrade yet
    assert(upgradingTransport.status == Status.Open)

    // Reject upgrade rejected
    val writeF = upgradingTransport.write(fullRequest)
    assert(await(writeq.poll) == fullRequest)
    val readF = upgradingTransport.read()
    assert(!readF.isDefined)
    assert(readq.offer(UpgradeRequestHandler.UpgradeAborted))

    intercept[UpgradeIgnoredException.type] { await(p) }
    assert(readq.offer(fullResponse))
    assert(await(readF) == fullResponse)

    // When the upgrade is aborted we revert to the parent behavior
    assert(upgradingTransport.status == Status.Closed)
  }
}
