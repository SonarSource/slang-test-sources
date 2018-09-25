package com.twitter.finagle.netty4.channel

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.proxy.{HttpProxyConnectHandler, Netty4ProxyConnectHandler}
import com.twitter.finagle.netty4.ssl.client.Netty4ClientSslChannelInitializer
import com.twitter.finagle.param.{Label, Logger, Stats}
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration
import io.netty.channel.{Channel, ChannelInitializer}
import io.netty.handler.proxy.{HttpProxyHandler, Socks5ProxyHandler}
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import java.util.logging.Level

/**
 * Base initializer which installs read / write timeouts and a connection handler
 */
private[netty4] abstract class AbstractNetty4ClientChannelInitializer(params: Stack.Params)
    extends ChannelInitializer[Channel] {

  import Netty4ClientChannelInitializer._

  private[this] val Transport.Liveness(readTimeout, writeTimeout, _) = params[Transport.Liveness]
  private[this] val Logger(logger) = params[Logger]
  private[this] val Label(label) = params[Label]
  private[this] val Stats(stats) = params[Stats]
  private[this] val Transporter.HttpProxyTo(httpHostAndCredentials) =
    params[Transporter.HttpProxyTo]
  private[this] val Transporter.SocksProxy(socksAddress, socksCredentials) =
    params[Transporter.SocksProxy]
  private[this] val Transporter.HttpProxy(httpAddress, httpCredentials) =
    params[Transporter.HttpProxy]

  private[this] val channelSnooper =
    if (params[Transport.Verbose].enabled)
      Some(ChannelSnooper.byteSnooper(label)(logger.log(Level.INFO, _, _)))
    else
      None

  private[this] val (sharedChannelRequestStats, sharedChannelStats) =
    if (!stats.isNull)
      (Some(new ChannelRequestStatsHandler.SharedChannelRequestStats(stats)), Some(new ChannelStatsHandler.SharedChannelStats(stats)))
    else
      (None, None)

  private[this] val exceptionHandler = new ChannelExceptionHandler(stats, logger)

  def initChannel(ch: Channel): Unit = {

    // first => last
    // - a request flies from last to first
    // - a response flies from first to last
    //
    // http proxy => ssl => read timeout => write timeout => ...
    // ... => channel stats => req stats => exceptions

    val pipe = ch.pipeline

    sharedChannelStats.foreach { sharedStats =>
      val channelStatsHandler = new ChannelStatsHandler(sharedStats)
      pipe.addFirst(ChannelStatsHandlerKey, channelStatsHandler)
    }

    channelSnooper.foreach(pipe.addFirst(ChannelLoggerHandlerKey, _))

    sharedChannelRequestStats.foreach { sharedStats =>
      val channelRequestStatsHandler = new ChannelRequestStatsHandler(sharedStats)
      pipe.addLast(ChannelRequestStatsHandlerKey, channelRequestStatsHandler)
    }

    if (readTimeout.isFinite && readTimeout > Duration.Zero) {
      val (timeoutValue, timeoutUnit) = readTimeout.inTimeUnit
      pipe.addFirst(ReadTimeoutHandlerKey, new ReadTimeoutHandler(timeoutValue, timeoutUnit))
    }

    if (writeTimeout.isFinite && writeTimeout > Duration.Zero) {
      val (timeoutValue, timeoutUnit) = writeTimeout.inTimeUnit
      pipe.addLast(WriteTimeoutHandlerKey, new WriteTimeoutHandler(timeoutValue, timeoutUnit))
    }

    pipe.addLast("exceptionHandler", exceptionHandler)

    // Add SSL/TLS Channel Initializer to the pipeline.
    pipe.addFirst("sslInit", new Netty4ClientSslChannelInitializer(params))

    // SOCKS5 proxy via `Netty4ProxyConnectHandler`.
    socksAddress.foreach { sa =>
      val proxyHandler = socksCredentials match {
        case None => new Socks5ProxyHandler(sa)
        case Some((u, p)) => new Socks5ProxyHandler(sa, u, p)
      }

      // Use only Finagle's session acquisition timeout
      proxyHandler.setConnectTimeoutMillis(0)

      pipe.addFirst(
        "socksProxyConnect",
        new Netty4ProxyConnectHandler(proxyHandler, bypassLocalhostConnections = true)
      )
    }

    // HTTP proxy via `Netty4ProxyConnectHandler`.
    httpAddress.foreach { sa =>
      val proxyHandler = httpCredentials match {
        case None => new HttpProxyHandler(sa)
        case Some(c) => new HttpProxyHandler(sa, c.username, c.password)
      }

      // Use only Finagle's session acquisition timeout
      proxyHandler.setConnectTimeoutMillis(0)

      // TODO: Figure out if it makes sense to bypass localhost connections when HTTP proxy is
      // enabled (see CSL-4409).
      pipe.addFirst("httpProxyConnect", new Netty4ProxyConnectHandler(proxyHandler))
    }

    // TCP tunneling via HTTP proxy (using `HttpProxyConnectHandler`).
    httpHostAndCredentials.foreach {
      case (host, credentials) =>
        pipe.addFirst("httpProxyConnect", new HttpProxyConnectHandler(host, credentials))
    }
  }
}
