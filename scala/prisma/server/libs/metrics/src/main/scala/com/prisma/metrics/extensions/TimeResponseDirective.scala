package com.prisma.metrics.extensions

import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.RouteResult.{Complete, Rejected}
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LoggingMagnet}
import com.prisma.metrics.{CustomTag, DefaultMetricsManager, MetricsManager, TimerMetric}

trait TimeResponseDirective {

  /**
    * The timer metric to use.
    */
  val requestTimer: TimerMetric

  /**
    * Captures the time it takes for a request to finish and sends it to a timer metric along with the status.
    */
  def captureResponseTimeFunction(
      loggingAdapter: LoggingAdapter,
      requestTimestamp: Long,
      level: LogLevel = Logging.InfoLevel
  )(req: HttpRequest)(res: Any): Unit = {
    res match {
      case Complete(resp) =>
        val responseTimestamp: Long = System.nanoTime
        val elapsedTime: Long       = (responseTimestamp - requestTimestamp) / 1000000
        requestTimer.record(elapsedTime, Seq(resp.status.toString()))

      case Rejected(_) =>
    }
  }

  def captureResponseTime(log: LoggingAdapter) = {
    val requestTimestamp = System.nanoTime
    captureResponseTimeFunction(log, requestTimestamp)(_)
  }

  val timeResponse = DebuggingDirectives.logRequestResult(LoggingMagnet(captureResponseTime(_)))
}

object TimeResponseDirectiveImpl extends TimeResponseDirective {
  val requestTimer: TimerMetric = DefaultMetricsManager.defineTimer("responseTime", CustomTag("status"))
}
