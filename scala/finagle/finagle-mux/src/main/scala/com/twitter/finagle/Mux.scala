package com.twitter.finagle

import com.twitter.conversions.storage._
import com.twitter.finagle.Mux.param.{MaxFrameSize, OppTls}
import com.twitter.finagle.client._
import com.twitter.finagle.naming.BindingFactory
import com.twitter.finagle.filter.{NackAdmissionFilter, PayloadSizeFilter}
import com.twitter.finagle.mux.Handshake.Headers
import com.twitter.finagle.mux.pushsession._
import com.twitter.finagle.mux.transport._
import com.twitter.finagle.mux.{Handshake, OpportunisticTlsParams, Request, Response}
import com.twitter.finagle.netty4.pushsession.{Netty4PushListener, Netty4PushTransporter}
import com.twitter.finagle.netty4.ssl.server.Netty4ServerSslChannelInitializer
import com.twitter.finagle.netty4.ssl.client.Netty4ClientSslChannelInitializer
import com.twitter.finagle.param.{Label, ProtocolLibrary, Stats, Timer, WithDefaultLoadBalancer}
import com.twitter.finagle.pool.SingletonPool
import com.twitter.finagle.pushsession._
import com.twitter.finagle.server._
import com.twitter.finagle.tracing._
import com.twitter.finagle.transport.Transport.{ClientSsl, ServerSsl}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{Buf, ByteReader}
import com.twitter.logging.Logger
import com.twitter.util.{Future, StorageUnit}
import io.netty.channel.{Channel, ChannelPipeline}
import java.net.{InetSocketAddress, SocketAddress}
import java.util.concurrent.Executor

/**
 * A client and server for the mux protocol described in [[com.twitter.finagle.mux]].
 */
object Mux extends Client[mux.Request, mux.Response] with Server[mux.Request, mux.Response] {
  private val log = Logger.get()

  /**
   * The current version of the mux protocol.
   */
  val LatestVersion: Short = 0x0001

  /**
   * Mux-specific stack params.
   */
  object param {

    /**
     * A class eligible for configuring the maximum size of a mux frame.
     * Any message that is larger than this value is fragmented across multiple
     * transmissions. Clients and Servers can use this to set an upper bound
     * on the size of messages they are willing to receive. The value is exchanged
     * and applied during the mux handshake.
     */
    case class MaxFrameSize(size: StorageUnit) {
      assert(size.inBytes <= Int.MaxValue, s"$size is not <= Int.MaxValue bytes")
      assert(size.inBytes > 0, s"$size must be positive")

      def mk(): (MaxFrameSize, Stack.Param[MaxFrameSize]) =
        (this, MaxFrameSize.param)
    }
    object MaxFrameSize {
      implicit val param = Stack.Param(MaxFrameSize(Int.MaxValue.bytes))
    }

    /**
     * A class eligible for configuring if a client's TLS mode is opportunistic.
     * If it's not None, then mux will negotiate with the supplied level whether
     * to use TLS or not before setting up TLS.
     *
     * If it's None, it will not attempt to negotiate whether to use TLS or not
     * with the remote peer, and if TLS is configured, it will use mux over TLS.
     *
     * @note opportunistic TLS is not mutually intelligible with simple mux
     *       over TLS
     */
    case class OppTls(level: Option[OpportunisticTls.Level]) {
      def mk(): (OppTls, Stack.Param[OppTls]) =
        (this, OppTls.param)
    }
    object OppTls {
      implicit val param = Stack.Param(OppTls(None))

      /** Determine whether opportunistic TLS is configured to `Desired` or `Required`. */
      def enabled(params: Stack.Params): Boolean = params[OppTls].level match {
        case Some(OpportunisticTls.Desired | OpportunisticTls.Required) => true
        case _ => false
      }
    }

    /**
     * A class eligible for configuring how to enable TLS.
     *
     * Only for internal use and testing--not intended to be exposed for
     * configuration to end-users.
     */
    private[finagle] case class TurnOnTlsFn(fn: (Stack.Params, ChannelPipeline) => Unit)
    private[finagle] object TurnOnTlsFn {
      implicit val param = Stack.Param(TurnOnTlsFn((_: Stack.Params, _: ChannelPipeline) => ()))
    }

    // tells the Netty4Transporter not to turn on TLS so we can turn it on later
    private[finagle] def removeTlsIfOpportunisticClient(params: Stack.Params): Stack.Params = {
      params[param.OppTls].level match {
        case None => params
        case _ => params + Transport.ClientSsl(None)
      }
    }

    // tells the Netty4Listener not to turn on TLS so we can turn it on later
    private[finagle] def removeTlsIfOpportunisticServer(params: Stack.Params): Stack.Params = {
      params[param.OppTls].level match {
        case None => params
        case _ => params + Transport.ServerSsl(None)
      }
    }

    private[finagle] case class PingManager(builder: (Executor, MessageWriter) => ServerPingManager)

    private[finagle] object PingManager {
      implicit val param = Stack.Param(PingManager { (_, writer) =>
        ServerPingManager.default(writer) })
    }
  }

  object Client {

    /** Prepends bound residual paths to outbound Mux requests's destinations. */
    private object MuxBindingFactory extends BindingFactory.Module[mux.Request, mux.Response] {
      protected[this] def boundPathFilter(residual: Path) =
        Filter.mk[mux.Request, mux.Response, mux.Request, mux.Response] { (req, service) =>
          service(mux.Request(residual ++ req.destination, req.contexts, req.body))
        }
    }

    private[finagle] val tlsEnable: (Stack.Params, ChannelPipeline) => Unit = (params, pipeline) =>
      pipeline.addFirst("opportunisticSslInit", new Netty4ClientSslChannelInitializer(params))

    private[finagle] val params: Stack.Params = StackClient.defaultParams +
      ProtocolLibrary("mux") +
      param.TurnOnTlsFn(tlsEnable)

    private val stack: Stack[ServiceFactory[mux.Request, mux.Response]] = StackClient.newStack
      .replace(StackClient.Role.pool, SingletonPool.module[mux.Request, mux.Response](allowInterrupts = true))
      .replace(BindingFactory.role, MuxBindingFactory)
      .prepend(PayloadSizeFilter.module(_.body.length, _.body.length))
      // Since NackAdmissionFilter should operate on all requests sent over
      // the wire including retries, it must be below `Retries`. Since it
      // aggregates the status of the entire cluster, it must be above
      // `LoadBalancerFactory` (not part of the endpoint stack).
      .insertBefore(
        StackClient.Role.prepFactory,
        NackAdmissionFilter.module[mux.Request, mux.Response]
      )

    /**
     * Returns the headers that a client sends to a server.
     *
     * @param maxFrameSize the maximum mux fragment size the client is willing to
     * receive from a server.
     */
    private[finagle] def headers(
      maxFrameSize: StorageUnit,
      tlsLevel: OpportunisticTls.Level
    ): Handshake.Headers = Seq(
      MuxFramer.Header.KeyBuf -> MuxFramer.Header.encodeFrameSize(maxFrameSize.inBytes.toInt),
      OpportunisticTls.Header.KeyBuf -> tlsLevel.buf)

    /**
     * Check the opportunistic TLS configuration to ensure it's in a consistent state
     */
    private[finagle] def validateTlsParamConsistency(params: Stack.Params): Unit = {
      if (param.OppTls.enabled(params) && params[ClientSsl].sslClientConfiguration.isEmpty) {
        val level = params[param.OppTls].level
        throw new IllegalStateException(
          s"Client desired opportunistic TLS ($level) but ClientSsl param is empty.")
      }
    }
  }

  final case class Client(
    stack: Stack[ServiceFactory[mux.Request, mux.Response]] = Mux.Client.stack,
    params: Stack.Params = Mux.Client.params
  ) extends PushStackClient[mux.Request, mux.Response, Client]
    with WithDefaultLoadBalancer[Client]
    with OpportunisticTlsParams[Client] {

    private[this] val scopedStatsParams = params + Stats(
      params[Stats].statsReceiver.scope("mux"))

    protected type SessionT = MuxClientNegotiatingSession
    protected type In = ByteReader
    protected type Out = Buf

    protected def newSession(
      handle: PushChannelHandle[ByteReader, Buf]
    ): Future[MuxClientNegotiatingSession] = {
      val negotiator: Option[Headers] => Future[MuxClientSession] = {
        headers => new Negotiation.Client(scopedStatsParams).negotiateAsync(handle, headers)
      }
      val headers = Mux.Client.headers(
        params[MaxFrameSize].size, params[OppTls].level.getOrElse(OpportunisticTls.Off))

      Future.value(
        new MuxClientNegotiatingSession(
          handle = handle,
          version = Mux.LatestVersion,
          negotiator = negotiator,
          headers = headers,
          name = params[Label].label,
          stats = scopedStatsParams[Stats].statsReceiver))
    }

    override def newClient(
      dest: Name,
      label0: String
    ): ServiceFactory[Request, Response] = {
      // We want to fail fast if the client's TLS configuration is inconsistent
      Mux.Client.validateTlsParamConsistency(params)
      super.newClient(dest, label0)
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

    protected def toService(
      session: MuxClientNegotiatingSession
    ): Future[Service[Request, Response]] =
      session.negotiate().flatMap(_.asService)

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]],
      params: Stack.Params
    ): Client = copy(stack, params)
  }

  def client: Client = Client()

  def newService(dest: Name, label: String): Service[mux.Request, mux.Response] =
    client.newService(dest, label)

  def newClient(dest: Name, label: String): ServiceFactory[mux.Request, mux.Response] =
    client.newClient(dest, label)

  object Server {

    private[finagle] val stack: Stack[ServiceFactory[mux.Request, mux.Response]] = StackServer.newStack
      .remove(TraceInitializerFilter.role)
      .prepend(PayloadSizeFilter.module(_.body.length, _.body.length))

    private[finagle] val tlsEnable: (Stack.Params, ChannelPipeline) => Unit = (params, pipeline) =>
      pipeline.addFirst("opportunisticSslInit", new Netty4ServerSslChannelInitializer(params))

    private[finagle] val params: Stack.Params = StackServer.defaultParams +
      ProtocolLibrary("mux") +
      param.TurnOnTlsFn(tlsEnable)

    type SessionF = (
      RefPushSession[ByteReader, Buf],
        Stack.Params,
        MuxChannelHandle,
        Service[Request, Response]
      ) => PushSession[ByteReader, Buf]

    val defaultSessionFactory: SessionF = (
    ref: RefPushSession[ByteReader, Buf],
    params: Stack.Params,
    handle: MuxChannelHandle,
    service: Service[Request, Response]
    ) => {
      val scopedStatsParams = params + Stats(
        params[Stats].statsReceiver.scope("mux"))
      MuxServerNegotiator.build(
        ref = ref,
        handle = handle,
        service = service,
        makeLocalHeaders = Mux.Server
          .headers(_: Headers, params[MaxFrameSize].size,
            params[OppTls].level.getOrElse(OpportunisticTls.Off)),
        negotiate = (service, headers) =>
          new Negotiation.Server(scopedStatsParams, service).negotiate(handle, headers),
        timer = params[Timer].timer
      )
      ref
    }

    /**
     * Returns the headers that a server sends to a client.
     *
     * @param clientHeaders The headers received from the client. This is useful since
     * the headers the server responds with can be based on the clients.
     *
     * @param maxFrameSize the maximum mux fragment size the server is willing to
     * receive from a client.
     */
    private[finagle] def headers(
      clientHeaders: Handshake.Headers,
      maxFrameSize: StorageUnit,
      tlsLevel: OpportunisticTls.Level
    ): Handshake.Headers = Seq(
      MuxFramer.Header.KeyBuf -> MuxFramer.Header.encodeFrameSize(maxFrameSize.inBytes.toInt),
      OpportunisticTls.Header.KeyBuf -> tlsLevel.buf)

    /**
     * Check the opportunistic TLS configuration to ensure it's in a consistent state
     */
    private[finagle] def validateTlsParamConsistency(params: Stack.Params): Unit = {
      // We need to make sure
      if (param.OppTls.enabled(params) && params[ServerSsl].sslServerConfiguration.isEmpty) {
        val level = params[param.OppTls].level
        throw new IllegalStateException(
          s"Server desired opportunistic TLS ($level) but ServerSsl param is empty.")
      }
    }
  }

  final case class Server(
    stack: Stack[ServiceFactory[mux.Request, mux.Response]] = Mux.Server.stack,
    params: Stack.Params = Mux.Server.params,
    sessionFactory: Server.SessionF = Server.defaultSessionFactory
  ) extends PushStackServer[mux.Request, mux.Response, Server]
    with OpportunisticTlsParams[Server] {

    protected type PipelineReq = ByteReader
    protected type PipelineRep = Buf

    protected def newListener(): PushListener[ByteReader, Buf] = {
      Mux.Server.validateTlsParamConsistency(params)
      new Netty4PushListener[ByteReader, Buf](
        pipelineInit = PipelineInit,
        params = Mux.param.removeTlsIfOpportunisticServer(params),
        setupMarshalling = identity
      ) {
        override protected def initializePushChannelHandle(
          ch: Channel,
          sessionFactory: SessionFactory
        ): Unit = {
          val proxyFactory: SessionFactory = { handle =>
            // We need to proxy via the MuxChannelHandle to get a vector
            // into the netty pipeline for handling installing the TLS
            // components of the pipeline after the negotiation.
            sessionFactory(new MuxChannelHandle(handle, ch, params))
          }
          super.initializePushChannelHandle(ch, proxyFactory)
        }
      }
    }

    protected def newSession(
      handle: PushChannelHandle[ByteReader, Buf],
      service: Service[Request, Response]
    ): RefPushSession[ByteReader, Buf] = handle match {
      case h: MuxChannelHandle =>
        val ref = new RefPushSession[ByteReader, Buf](h, SentinelSession[ByteReader, Buf](h))
        sessionFactory(ref, params, h, service)
        ref

      case other =>
        throw new IllegalStateException(
          s"Expected to find a `MuxChannelHandle` but found ${other.getClass.getSimpleName}")
    }


    protected def copy1(
      stack: Stack[ServiceFactory[Request, Response]],
      params: Stack.Params
    ): Server = copy(stack, params)
  }

  def server: Server = Server()

  def serve(
    addr: SocketAddress,
    service: ServiceFactory[mux.Request, mux.Response]
  ): ListeningServer = server.serve(addr, service)
}
