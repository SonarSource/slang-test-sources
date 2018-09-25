package com.twitter.finagle.mux

import com.twitter.finagle.{Mux, Service, ServiceFactory, Stack, mux, param}
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.mux.pushsession._
import com.twitter.finagle.netty4.pushsession.Netty4PushTransporter
import com.twitter.finagle.pushsession.{PushChannelHandle, PushSession, PushStackClient, PushTransporter}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.{Buf, ByteReader}
import com.twitter.util.Future
import io.netty.channel.{Channel, ChannelPipeline}
import java.net.InetSocketAddress

// Implementation of the standard mux client that doesn't attempt to negotiate.
// Only useful for testing Smux to ensure that failing to negotiate doesn't circumvent TLS.

final case class NonNegotiatingClient(
  stack: Stack[ServiceFactory[mux.Request, mux.Response]] = Mux.Client().stack,
  params: Stack.Params = Mux.Client.params
) extends PushStackClient[mux.Request, mux.Response, NonNegotiatingClient] {

  private[this] val scopedStatsParams = params + param.Stats(
    params[param.Stats].statsReceiver.scope("mux"))

  protected type SessionT = MuxClientSession
  protected type In = ByteReader
  protected type Out = Buf

  protected def newSession(
    handle: PushChannelHandle[ByteReader, Buf]
  ): Future[MuxClientSession] = {

    val statsReceiver: StatsReceiver = params[param.Stats].statsReceiver
    val framerStats = statsReceiver.scope("framer")

    Future.value(
      new MuxClientSession(
        handle = handle,
        h_decoder = new FragmentDecoder(framerStats),
        h_messageWriter = new FragmentingMessageWriter(handle, Int.MaxValue, framerStats),
        detectorConfig = params[FailureDetector.Param].param,
        name = params[param.Label].label,
        params[param.Stats].statsReceiver,
        params[param.Timer].timer
      ))
  }

  protected def newPushTransporter(
    inetSocketAddress: InetSocketAddress
  ): PushTransporter[ByteReader, Buf] = {

    // We use a custom Netty4PushTransporter to provide a handle to the
    // underlying Netty channel via MuxChannelHandle, giving us the ability to
    // add TLS support later in the lifecycle of the socket connection.
    new Netty4PushTransporter[ByteReader, Buf](
      transportInit = _ => (),
      protocolInit = PipelineInit,
      remoteAddress = inetSocketAddress,
      params = Mux.param.removeTlsIfOpportunisticClient(params)
    ) {
      override protected def initSession[T <: PushSession[ByteReader, Buf]](
        channel: Channel,
        protocolInit: (ChannelPipeline) => Unit,
        sessionBuilder: (PushChannelHandle[ByteReader, Buf]) => Future[T]
      ): Future[T] = {
        // With this builder we add support for opportunistic TLS via `MuxChannelHandle`
        // and the respective `Negotation` types. Adding more proxy types will break this pathway.
        def wrappedBuilder(pushChannelHandle: PushChannelHandle[ByteReader, Buf]): Future[T] =
          sessionBuilder(new MuxChannelHandle(pushChannelHandle, channel, scopedStatsParams))

        super.initSession(channel, protocolInit, wrappedBuilder)
      }
    }
  }

  protected def toService(session: MuxClientSession): Future[Service[Request, Response]] =
    session.asService

  protected def copy1(
    stack: Stack[ServiceFactory[Request, Response]],
    params: Stack.Params
  ): NonNegotiatingClient = copy(stack, params)
}
