package org.http4s
package client
package middleware

import cats.effect._
import fs2._
import org.http4s.util.CaseInsensitiveString
import org.log4s.{Logger ⇒ SLogger}

/**
  * Simple Middleware for Logging All Requests and Responses
  */
object Logger {
  def apply[F[_]: Effect](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
  )(client: Client[F]): Client[F] =
    ResponseLogger.apply0(logHeaders, logBody, redactHeadersWhen)(
      RequestLogger.apply0(logHeaders, logBody, redactHeadersWhen)(
        client
      )
    )

  def logMessage[F[_], A <: Message[F]](message: A)(
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains)(
      logger: SLogger)(implicit F: Effect[F]): F[Unit] = {

    val charset = message.charset
    val isBinary = message.contentType.exists(_.mediaType.binary)
    val isJson = message.contentType.exists(mT =>
      mT.mediaType == MediaType.`application/json` || mT.mediaType == MediaType.`application/hal+json`)

    val isText = !isBinary || isJson

    def prelude = message match {
      case Request(method, uri, httpVersion, _, _, _) =>
        s"$httpVersion $method $uri"

      case Response(status, httpVersion, _, _, _) =>
        s"$httpVersion $status"
    }

    val headers =
      if (logHeaders)
        message.headers.redactSensitive(redactHeadersWhen).toList.mkString("Headers(", ", ", ")")
      else ""

    val bodyStream = if (logBody && isText) {
      message.bodyAsText(charset.getOrElse(Charset.`UTF-8`))
    } else if (logBody) {
      message.body
        .fold(new StringBuilder)((sb, b) => sb.append(java.lang.Integer.toHexString(b & 0xff)))
        .map(_.toString)
    } else {
      Stream.empty.covary[F]
    }

    val bodyText = if (logBody) {
      bodyStream.fold("")(_ + _).map(text => s"""body="$text"""")
    } else {
      Stream("").covary[F]
    }

    if (!logBody && !logHeaders) F.unit
    else {
      bodyText
        .map(body => s"$prelude $headers $body")
        .map(text => logger.info(text))
        .compile
        .drain
    }
  }
}
