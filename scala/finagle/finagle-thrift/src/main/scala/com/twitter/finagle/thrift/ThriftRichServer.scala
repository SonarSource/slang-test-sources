package com.twitter.finagle.thrift

import com.twitter.finagle.param.Stats
import com.twitter.finagle.stats._
import com.twitter.finagle.{ListeningServer, Server, Stack, Thrift}
import java.net.SocketAddress
import org.apache.thrift.protocol.TProtocolFactory

/**
 * A mixin trait to provide a rich Thrift server API.
 *
 * @define serveIface
 *
 * Serve the interface implementation `iface`, which must be generated
 * by either [[https://github.com/twitter/scrooge Scrooge]] or
 * [[https://github.com/mariusaeriksen/thrift-finagle thrift-finagle]].
 *
 * Given the IDL:
 *
 * {{{
 * service TestService {
 *   string query(1: string x)
 * }
 * }}}
 *
 * Scrooge will generate an interface, `TestService.FutureIface`,
 * implementing the above IDL.
 *
 * $serverExample
 *
 * Note that this interface is discovered by reflection. Passing an
 * invalid interface implementation will result in a runtime error.
 *
 * @define serverExample
 *
 * `TestService.FutureIface` must be implemented and passed
 * into `serveIface`:
 *
 * {{{
 * $serverExampleObject.serveIface(":*", new TestService.FutureIface {
 *   def query(x: String) = Future.value(x)  // (echo service)
 * })
 * }}}
 *
 * @define serverExampleObject ThriftMuxRichServer
 *
 * @define serveIfaces
 *
 * Serve multiple interfaces:
 *
 * {{{
 * val serviceMap = Map(
 * "echo" -> new EchoService(),
 * "extendedEcho" -> new ExtendedEchoService()
 * )
 *
 * val server = Thrift.server.serveIfaces(address, serviceMap)
 * }}}
 *
 * A default service name can be specified, so we can upgrade an
 * existing non-multiplexed server to a multiplexed one without
 * breaking the old clients:
 *
 * {{{
 * val server = Thrift.server.serveIfaces(
 *   address, serviceMap, defaultService = Some("extendedEcho"))
 * }}}
 */
trait ThriftRichServer { self: Server[Array[Byte], Array[Byte]] =>
  import ThriftUtil._

  protected def serverParam: RichServerParam

  protected def protocolFactory: TProtocolFactory

  protected def maxThriftBufferSize: Int = Thrift.param.maxThriftBufferSize

  protected def serverLabel: String = "thrift"

  protected def params: Stack.Params

  protected def serverStats: StatsReceiver = params[Stats].statsReceiver

  /**
   * $serveIface
   */
  def serveIface(addr: String, iface: AnyRef): ListeningServer =
    serve(addr, serverFromIface(iface, serverParam))

  /**
   * $serveIface
   */
  def serveIface(addr: SocketAddress, iface: AnyRef): ListeningServer =
    serve(addr, serverFromIface(iface, serverParam))

  /**
   * $serveIfaces
   */
  def serveIfaces(
    addr: String,
    ifaces: Map[String, AnyRef],
    defaultService: Option[String] = None
  ): ListeningServer =
    serve(addr, serverFromIfaces(ifaces, defaultService, serverParam))

  /**
   * $serveIfaces
   */
  def serveIfaces(addr: SocketAddress, ifaces: Map[String, AnyRef]): ListeningServer =
    serve(addr, serverFromIfaces(ifaces, None, serverParam))

  /**
   * $serveIfaces
   */
  def serveIfaces(
    addr: SocketAddress,
    ifaces: Map[String, AnyRef],
    defaultService: Option[String]
  ): ListeningServer =
    serve(addr, serverFromIfaces(ifaces, defaultService, serverParam))
}

