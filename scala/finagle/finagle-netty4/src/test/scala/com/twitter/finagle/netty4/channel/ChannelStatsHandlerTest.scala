package com.twitter.finagle.netty4.channel

import com.twitter.finagle.netty4.channel.ChannelStatsHandler.SharedChannelStats
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.TimeConversions.intToTimeableNumber
import com.twitter.util.{Duration, Time}
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.channel._
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.TimeoutException
import org.mockito.Mockito.when
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

class ChannelStatsHandlerTest extends FunSuite with MockitoSugar {

  trait SocketTest {
    val chan = mock[Channel]
    val ctx = mock[ChannelHandlerContext]

    when(chan.isWritable).thenReturn(false, true, false)
    when(ctx.channel).thenReturn(chan)
  }

  private trait InMemoryStatsTest extends SocketTest {
    val sr = new InMemoryStatsReceiver()
    val handler = new ChannelStatsHandler(new ChannelStatsHandler.SharedChannelStats(sr))
    handler.handlerAdded(ctx)
  }

  test("counters are collected correctly") {
    Time.withCurrentTimeFrozen { control =>
      new InMemoryStatsTest {
        control.advance(5.minutes)
        handler.channelWritabilityChanged(ctx)
        assert(sr.counters(Seq("socket_writable_ms")) == 5.minutes.inMillis)

        control.advance(10.minutes)
        handler.channelWritabilityChanged(ctx)
        assert(sr.counters(Seq("socket_unwritable_ms")) == 10.minutes.inMillis)

        control.advance(20.minutes)
        handler.channelWritabilityChanged(ctx)
        assert(sr.counters(Seq("socket_writable_ms")) == 25.minutes.inMillis)
        assert(sr.counters(Seq("socket_unwritable_ms")) == 10.minutes.inMillis)
      }
    }
  }

  private class TestContext(
    sharedStats: SharedChannelStats
  ) {
    val ctx = mock[ChannelHandlerContext]
    val channelStatsHandler = new ChannelStatsHandler(sharedStats)
    private val chan = new EmbeddedChannel()
    private val start = Time.now

    channelStatsHandler.handlerAdded(ctx)
  }

  private def connectionCountEquals(sr: InMemoryStatsReceiver, num: Float): Unit = {
    assert(sr.gauges(Seq("connections"))() == num)
  }

  test("ChannelStatsHandler counts connections") {
    val sr = new InMemoryStatsReceiver
    val sharedStats = new SharedChannelStats(sr)
    val ctx1 = new TestContext(sharedStats)
    val ctx2 = new TestContext(sharedStats)
    val handler1 = ctx1.channelStatsHandler
    val handler2 = ctx2.channelStatsHandler

    connectionCountEquals(sr, 0)

    handler1.channelActive(ctx1.ctx)
    connectionCountEquals(sr, 1)

    handler2.channelActive(ctx2.ctx)
    connectionCountEquals(sr, 2)

    handler1.channelInactive(ctx1.ctx)
    connectionCountEquals(sr, 1)

    handler2.channelInactive(ctx2.ctx)
    connectionCountEquals(sr, 0)
  }

  test("ChannelStatsHandler handles multiple channelInactive calls") {
    val sr = new InMemoryStatsReceiver
    val sharedStats = new SharedChannelStats(sr)
    val ctx1 = new TestContext(sharedStats)
    val handler = ctx1.channelStatsHandler

    connectionCountEquals(sr, 0)

    handler.channelActive(ctx1.ctx)
    connectionCountEquals(sr, 1)

    handler.channelInactive(ctx1.ctx)
    connectionCountEquals(sr, 0)

    handler.channelInactive(ctx1.ctx)
    connectionCountEquals(sr, 0)
  }

  private def channelLifeCycleTest(
    counterName: String,
    f: (ChannelDuplexHandler, ChannelHandlerContext) => Unit
  ) = test(s"ChannelStatsHandler counts $counterName") {
    val sr = new InMemoryStatsReceiver
    val sharedStats = new SharedChannelStats(sr)
    val ctx = new TestContext(sharedStats)
    val handler = ctx.channelStatsHandler

    assert(sr.counters(Seq(counterName)) == 0)
    f(handler, ctx.ctx)
    assert(sr.counters(Seq(counterName)) == 1)
  }

  channelLifeCycleTest(
    "closes",
    (handler, ctx) => handler.close(ctx, mock[ChannelPromise])
  )

  channelLifeCycleTest(
    "connects",
    (handler, ctx) => handler.channelActive(ctx)
  )

  test("ChannelStatsHandler records connection duration") {
    Time.withCurrentTimeFrozen { control =>
      val sr = new InMemoryStatsReceiver
      val sharedStats = new SharedChannelStats(sr)
      val ctx1 = new TestContext(sharedStats)
      val ctx2 = new TestContext(sharedStats)

      val handler1 = ctx1.channelStatsHandler
      val handler2 = ctx2.channelStatsHandler

      handler1.channelActive(ctx1.ctx)
      handler2.channelActive(ctx2.ctx)
      control.advance(Duration.fromMilliseconds(100))
      handler1.channelInactive(ctx1.ctx)
      assert(sr.stat("connection_duration")() == Seq(100.0))
      control.advance(Duration.fromMilliseconds(200))
      handler2.channelInactive(ctx2.ctx)
      assert(sr.stat("connection_duration")() == Seq(100.0, 300.0))
    }
  }

  test("ChannelStatsHandler counts exceptions") {
    val sr = new InMemoryStatsReceiver
    val sharedStats = new SharedChannelStats(sr)
    val ctx = new TestContext(sharedStats)
    val handler = ctx.channelStatsHandler

    handler.exceptionCaught(ctx.ctx, new RuntimeException)
    handler.exceptionCaught(ctx.ctx, new TimeoutException)
    handler.exceptionCaught(ctx.ctx, new Exception)
    assert(sr.counters(Seq("exn", "java.lang.RuntimeException")) == 1)
    assert(sr.counters(Seq("exn", "java.lang.Exception")) == 1)
    assert(sr.counters(Seq("exn", "java.util.concurrent.TimeoutException")) == 1)
  }

  test("ChannelStatsHandler counts sent and received bytes") {
    val sr = new InMemoryStatsReceiver
    val sharedStats = new SharedChannelStats(sr)
    val ctx1 = new TestContext(sharedStats)
    val handler1 = ctx1.channelStatsHandler

    // note: if `handlerAdded` is called it'd overwrite our setup
    handler1.channelActive(ctx1.ctx)
    handler1.write(ctx1.ctx, wrappedBuffer(Array.fill(42)(0.toByte)), mock[ChannelPromise])
    handler1.channelInactive(ctx1.ctx)

    assert(sr.counter("sent_bytes")() == 42)
    assert(sr.stat("connection_received_bytes")() == Seq(0.0))
    assert(sr.stat("connection_sent_bytes")() == Seq(42.0))

    val ctx2 = new TestContext(sharedStats)
    val handler2 = ctx2.channelStatsHandler

    handler2.channelActive(ctx2.ctx)
    handler2.channelRead(ctx2.ctx, wrappedBuffer(Array.fill(123)(0.toByte)))
    handler2.channelInactive(ctx2.ctx)

    assert(sr.counter("received_bytes")() == 123)
    assert(sr.stat("connection_received_bytes")() == Seq(0.0, 123.0))
    assert(sr.stat("connection_sent_bytes")() == Seq(42.0, 0.0))
  }
}
