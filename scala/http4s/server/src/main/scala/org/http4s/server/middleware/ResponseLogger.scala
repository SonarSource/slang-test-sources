package org.http4s
package server
package middleware

import cats.data.{Kleisli, OptionT}
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.util.CaseInsensitiveString
import org.log4s.getLogger
import scala.concurrent.ExecutionContext

/**
  * Simple middleware for logging responses as they are processed
  */
object ResponseLogger {
  private[this] val logger = getLogger

  def apply[F[_]](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
  )(service: HttpService[F])(
      implicit F: Effect[F],
      ec: ExecutionContext = ExecutionContext.global): HttpService[F] =
    Kleisli { req =>
      OptionT(
        service(req)
          .semiflatMap { response =>
            if (!logBody)
              Logger.logMessage[F, Response[F]](response)(logHeaders, logBody, redactHeadersWhen)(
                logger) *> F.delay(response)
            else
              async.refOf[F, Vector[Segment[Byte, Unit]]](Vector.empty[Segment[Byte, Unit]]).map {
                vec =>
                  val newBody = Stream
                    .eval(vec.get)
                    .flatMap(v => Stream.emits(v).covary[F])
                    .flatMap(c => Stream.segment(c).covary[F])

                  response.copy(
                    body = response.body
                    // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                      .observe(_.segments.flatMap(s => Stream.eval_(vec.modify(_ :+ s))))
                      .onFinalize {
                        Logger.logMessage[F, Response[F]](response.withBodyStream(newBody))(
                          logHeaders,
                          logBody,
                          redactHeadersWhen)(logger)
                      }
                  )
              }
          }
          .orElse(OptionT(F.delay(logger.info("service returned None")).as(None)))
          .value
          .handleErrorWith(t =>
            F.delay(logger.info(s"service raised an error: ${t.getClass}")) *> F
              .raiseError[Option[Response[F]]](t)))
    }
}
