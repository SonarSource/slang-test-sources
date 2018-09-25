package com.twitter.finagle.client

import com.twitter.finagle.{Filter, Service, param}
import com.twitter.finagle.service.{RequeueFilter, _}
import com.twitter.finagle.stats.{BlacklistStatsReceiver, StatsReceiver}
import com.twitter.logging.{Level, Logger}
import com.twitter.util.{Future, Stopwatch, Throw, Try}

/**
 * @see [[MethodBuilderScaladoc]]
 */
private[finagle] class MethodBuilderRetry[Req, Rep] private[client] (mb: MethodBuilder[Req, Rep]) {

  import MethodBuilderRetry._

  /**
   * @see [[MethodBuilderScaladoc.withRetryForClassifier]]
   */
  def forClassifier(
    classifier: ResponseClassifier
  ): MethodBuilder[Req, Rep] =
    mb.withConfig(mb.config.copy(retry = Config(classifier)))

  /**
   * @see [[MethodBuilderScaladoc.withRetryDisabled]]
   */
  def disabled: MethodBuilder[Req, Rep] =
    forClassifier(Disabled)

  private[client] def filter(
    scopedStats: StatsReceiver
  ): Filter.TypeAgnostic = {
    val classifier = mb.config.retry.responseClassifier
    if (classifier eq Disabled)
      Filter.TypeAgnostic.Identity
    else {
      new Filter.TypeAgnostic {
        def toFilter[Req1, Rep1]: Filter[Req1, Rep1, Req1, Rep1] = {
          val retryPolicy = policyForReqRep(shouldRetry[Req1, Rep1](classifier))
          val withoutRequeues = filteredPolicy(retryPolicy)

          new RetryFilter[Req1, Rep1](
            withoutRequeues,
            mb.params[param.HighResTimer].timer,
            scopedStats,
            mb.params[Retries.Budget].retryBudget
          )
        }
      }
    }
  }

  private[client] def logicalStatsFilter(stats: StatsReceiver): Filter.TypeAgnostic =
    StatsFilter.typeAgnostic(
      new BlacklistStatsReceiver(stats.scope(LogicalScope), LogicalStatsBlacklistFn),
      mb.config.retry.responseClassifier,
      mb.params[param.ExceptionStatsHandler].categorizer,
      mb.params[StatsFilter.Param].unit
    )

  private[client] def logFailuresFilter(
    clientName: String,
    methodName: Option[String]
  ): Filter.TypeAgnostic = new Filter.TypeAgnostic {
    def toFilter[Req1, Rep1]: Filter[Req1, Rep1, Req1, Rep1] = {
      val loggerPrefix = "com.twitter.finagle.client.MethodBuilder"
      val (label, loggerName) = methodName match {
        case Some(methodName) =>
          (s"$clientName/$methodName", s"$loggerPrefix.$clientName.$methodName")
        case None =>
          (clientName, s"$loggerPrefix.$clientName")
      }
      new LogFailuresFilter[Req1, Rep1](
        Logger.get(loggerName),
        label,
        mb.config.retry.responseClassifier,
        Stopwatch.systemMillis
      )
    }
  }

  private[client] def registryEntries: Iterable[(Seq[String], String)] = {
    Seq(
      (Seq("retry"), mb.config.retry.toString)
    )
  }

}

private[client] object MethodBuilderRetry {
  val MaxRetries = 2

  private val Disabled: ResponseClassifier =
    ResponseClassifier.named("Disabled")(PartialFunction.empty)

  private def shouldRetry[Req, Rep](
    classifier: ResponseClassifier
  ): PartialFunction[(Req, Try[Rep]), Boolean] =
    new PartialFunction[(Req, Try[Rep]), Boolean] {
      def isDefinedAt(reqRep: (Req, Try[Rep])): Boolean =
        classifier.isDefinedAt(ReqRep(reqRep._1, reqRep._2))
      def apply(reqRep: (Req, Try[Rep])): Boolean =
        classifier(ReqRep(reqRep._1, reqRep._2)) match {
          case ResponseClass.Failed(retryable) => retryable
          case _ => false
        }
    }

  private def policyForReqRep[Req, Rep](
    shouldRetry: PartialFunction[(Req, Try[Rep]), Boolean]
  ): RetryPolicy[(Req, Try[Rep])] =
    RetryPolicy.tries(
      MethodBuilderRetry.MaxRetries + 1, // add 1 for the initial request
      shouldRetry
    )

  private def isDefined(retryPolicy: RetryPolicy[_]): Boolean =
    retryPolicy != RetryPolicy.none

  private def filteredPolicy[Req, Rep](
    retryPolicy: RetryPolicy[(Req, Try[Rep])]
  ): RetryPolicy[(Req, Try[Rep])] =
    if (!isDefined(retryPolicy))
      retryPolicy
    else
      retryPolicy.filterEach[(Req, Try[Rep])] {
        // rely on finagle to handle these in the RequeueFilter
        // and avoid retrying them again.
        case (_, Throw(RequeueFilter.Requeueable(_))) => false
        case _ => true
      }

  private[client] class LogFailuresFilter[Req, Rep](
      logger: Logger,
      label: String,
      responseClassifier: ResponseClassifier,
      nowMs: () => Long)
    extends Filter[Req, Rep, Req, Rep] {

    def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
      val start = nowMs()
      service(request).respond { response =>
        val reqRep = ReqRep(request, response)
        responseClassifier.applyOrElse(reqRep, ResponseClassifier.Default) match {
          case ResponseClass.Failed(_) => log(start, request, response)
          case _ => // don't log successful responses
        }
      }
    }

    private[this] def log(startMs: Long, request: Req, response: Try[Rep]): Unit = {
      if (logger.isLoggable(Level.DEBUG)) {
        val elapsedMs = nowMs() - startMs
        val exception = response match {
          case Throw(e) => e
          case _ => null // note: nulls are allowed/ignored in this logging API
        }
        val msg = s"Request failed for $label, elapsed=$elapsedMs ms"
        if (logger.isLoggable(Level.TRACE)) {
          logger.trace(exception, s"$msg (request=$request, response=$response)")
        } else {
          logger.debug(exception, msg)
        }
      }
    }
  }

  /** The stats scope used for logical success rate. */
  private val LogicalScope = "logical"

  // the `StatsReceiver` used is already scoped to `$clientName/$methodName/logical`.
  // this omits the pending gauge as well as sourcedfailures details.
  private val LogicalStatsBlacklistFn: Seq[String] => Boolean = { segments =>
    val head = segments.head
    if (head == "pending" || head == "sourcedfailures") {
      true
    } else if (head == "failures" && segments.size == 1) {
      // only filter out the failures rollup while keeping the exception
      // details that are scoped via `ExceptionStatsHandler` to
      // $clientName/$methodName/logical/failures/<exception_name>
      true
    } else {
      false
    }
  }

  /**
   * @see [[MethodBuilderRetry.forClassifier]] for details on how the
   *     classifier is used.
   */
  case class Config(responseClassifier: ResponseClassifier)

}
