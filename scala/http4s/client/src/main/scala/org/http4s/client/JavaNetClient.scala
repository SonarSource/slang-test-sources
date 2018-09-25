package org.http4s
package client

import cats.data.Kleisli
import cats.effect.{Effect, Sync}
import cats.implicits._
import java.net.{HttpURLConnection, Proxy, URL}
import javax.net.ssl.{HostnameVerifier, HttpsURLConnection, SSLSocketFactory}
import org.http4s.internal.{blocking, readInputStream, writeOutputStream}
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

/** A [[Client]] based on `java.net.HttpUrlConnection`.
  *
  * `JavaNetClient` adds no dependencies beyond `http4s-client`.  This
  * client is generally not production grade, but convenient for
  * exploration in a REPL.
  *
  * All I/O operations in this client are blocking.
  *
  * @param blockingExecutionContext An `ExecutionContext` on which
  * blocking operations will be performed.
  * @param ec The `ExecutionContext` to which work will be shifted
  * back after blocking.
  */
sealed abstract class JavaNetClient private (
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val proxy: Option[Proxy],
    val hostnameVerifier: Option[HostnameVerifier],
    val sslSocketFactory: Option[SSLSocketFactory],
    val blockingExecutionContext: ExecutionContext
)(implicit ec: ExecutionContext) {
  private def copy(
      connectTimeout: Duration = connectTimeout,
      readTimeout: Duration = readTimeout,
      proxy: Option[Proxy] = proxy,
      hostnameVerifier: Option[HostnameVerifier] = hostnameVerifier,
      sslSocketFactory: Option[SSLSocketFactory] = sslSocketFactory,
      blockingExecutionContext: ExecutionContext = blockingExecutionContext
  ): JavaNetClient =
    new JavaNetClient(
      connectTimeout = connectTimeout,
      readTimeout = readTimeout,
      proxy = proxy,
      hostnameVerifier = hostnameVerifier,
      sslSocketFactory = sslSocketFactory,
      blockingExecutionContext = blockingExecutionContext
    ) {}

  def withConnectTimeout(connectTimeout: Duration): JavaNetClient =
    copy(connectTimeout = connectTimeout)

  def withReadTimeout(readTimeout: Duration): JavaNetClient =
    copy(readTimeout = readTimeout)

  def withProxyOption(proxy: Option[Proxy]): JavaNetClient =
    copy(proxy = proxy)
  def withProxy(proxy: Proxy): JavaNetClient =
    withProxyOption(Some(proxy))
  def withoutProxy: JavaNetClient =
    withProxyOption(None)

  def withHostnameVerifierOption(hostnameVerifier: Option[HostnameVerifier]): JavaNetClient =
    copy(hostnameVerifier = hostnameVerifier)
  def withHostnameVerifier(hostnameVerifier: HostnameVerifier): JavaNetClient =
    withHostnameVerifierOption(Some(hostnameVerifier))
  def withoutHostnameVerifier: JavaNetClient =
    withHostnameVerifierOption(None)

  def withSslSocketFactoryOption(sslSocketFactory: Option[SSLSocketFactory]): JavaNetClient =
    copy(sslSocketFactory = sslSocketFactory)
  def withSslSocketFactory(sslSocketFactory: SSLSocketFactory): JavaNetClient =
    withSslSocketFactoryOption(Some(sslSocketFactory))
  def withoutSslSocketFactory: JavaNetClient =
    withSslSocketFactoryOption(None)

  def withBlockingExecutionContext(blockingExecutionContext: ExecutionContext): JavaNetClient =
    copy(blockingExecutionContext = blockingExecutionContext)

  /** Creates the `JavaNetClient`.
    *
    * The shutdown of this client is a no-op.  Creation of the client
    * allocates no resources, and any resources allocated while using
    * this client are reclaimed by the JVM at its own leisure.
    */
  def create[F[_]](implicit F: Effect[F]): Client[F] = Client(open, F.unit)

  private def open[F[_]](implicit F: Effect[F]) = Kleisli { req: Request[F] =>
    for {
      url <- F.delay(new URL(req.uri.toString))
      conn <- openConnection(url)
      _ <- configureSsl(conn)
      _ <- F.delay(conn.setConnectTimeout(timeoutMillis(connectTimeout)))
      _ <- F.delay(conn.setReadTimeout(timeoutMillis(readTimeout)))
      _ <- F.delay(conn.setRequestMethod(req.method.renderString))
      _ <- F.delay(req.headers.foreach {
        case Header(name, value) => conn.setRequestProperty(name.value, value)
      })
      _ <- F.delay(conn.setInstanceFollowRedirects(false))
      _ <- F.delay(conn.setDoInput(true))
      resp <- blocking(fetchResponse(req, conn), blockingExecutionContext)
    } yield DisposableResponse(resp, F.delay(conn.getInputStream.close()))
  }

  private def fetchResponse[F[_]](req: Request[F], conn: HttpURLConnection)(implicit F: Effect[F]) =
    for {
      _ <- writeBody(req, conn)
      code <- F.delay(conn.getResponseCode)
      status <- F.fromEither(Status.fromInt(code))
      headers <- F.delay(
        Headers(
          conn.getHeaderFields.asScala
            .filter(_._1 != null)
            .flatMap { case (k, vs) => vs.asScala.map(Header(k, _)) }
            .toList
        ))
      body = readInputStream(F.delay(conn.getInputStream), 4096, blockingExecutionContext)
    } yield Response(status = status, headers = headers, body = body)

  private def timeoutMillis(d: Duration): Int = d match {
    case d: FiniteDuration if d > Duration.Zero => d.toMillis.max(0).min(Int.MaxValue).toInt
    case _ => 0
  }

  private def openConnection[F[_]](url: URL)(implicit F: Sync[F]) = proxy match {
    case Some(p) =>
      F.delay(url.openConnection(p).asInstanceOf[HttpURLConnection])
    case None =>
      F.delay(url.openConnection().asInstanceOf[HttpURLConnection])
  }

  private def writeBody[F[_]](req: Request[F], conn: HttpURLConnection)(implicit F: Effect[F]) =
    if (req.isChunked) {
      F.delay(conn.setDoOutput(true)) *>
        F.delay(conn.setChunkedStreamingMode(4096)) *>
        req.body
          .to(writeOutputStream(F.delay(conn.getOutputStream), blockingExecutionContext, false))
          .compile
          .drain
    } else
      req.contentLength match {
        case Some(len) if len >= 0L =>
          F.delay(conn.setDoOutput(true)) *>
            F.delay(conn.setFixedLengthStreamingMode(len)) *>
            req.body
              .to(writeOutputStream(F.delay(conn.getOutputStream), blockingExecutionContext, false))
              .compile
              .drain
        case _ =>
          F.delay(conn.setDoOutput(false))
      }

  private def configureSsl[F[_]](conn: HttpURLConnection)(implicit F: Sync[F]) =
    conn match {
      case connSsl: HttpsURLConnection =>
        for {
          _ <- hostnameVerifier.fold(F.unit)(hv => F.delay(connSsl.setHostnameVerifier(hv)))
          _ <- sslSocketFactory.fold(F.unit)(sslf => F.delay(connSsl.setSSLSocketFactory(sslf)))
        } yield ()
      case _ => F.unit
    }
}

object JavaNetClient {
  def apply(blockingExecutionContext: ExecutionContext)(
      implicit ec: ExecutionContext): JavaNetClient =
    new JavaNetClient(
      connectTimeout = Duration.Inf,
      readTimeout = Duration.Inf,
      proxy = None,
      hostnameVerifier = None,
      sslSocketFactory = None,
      blockingExecutionContext = blockingExecutionContext
    ) {}
}
