package com.twitter.finagle.liveness

import com.twitter.conversions.time._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.Ema
import com.twitter.util.{Duration, Stopwatch, WindowedAdder}

/**
 * A `FailureAccrualPolicy` is used by `FailureAccrualFactory` to determine
 * whether to mark an endpoint dead upon a request failure. On each successful
 * response, `FailureAccrualFactory` calls `recordSuccess()`. On each failure,
 * `FailureAccrualFactory` calls `markDeadOnFailure()` to obtain the duration
 * to mark the endpoint dead for; (Some(Duration)), or None.
 *
 * @see The [[https://twitter.github.io/finagle/guide/Clients.html#failure-accrual user guide]]
 *      for more details.
 */
abstract class FailureAccrualPolicy { self =>

  /** Invoked by FailureAccrualFactory when a request is successful. */
  def recordSuccess(): Unit

  /**
   * Invoked by FailureAccrualFactory when a non-probing request fails.
   * If it returns Some(Duration), the FailureAccrualFactory will mark the
   * endpoint dead for the specified Duration.
   */
  def markDeadOnFailure(): Option[Duration]

  /**
   * Invoked by FailureAccrualFactory when an endpoint is revived after
   * probing. Used to reset any history.
   */
  def revived(): Unit

  /**
   * Creates a [[FailureAccrualPolicy]] which uses both `this` and `that`.
   *
   * [[markDeadOnFailure]] will return the longer duration if both `this` and `that` return
   * Some(duration).
   */
  final def orElse(that: FailureAccrualPolicy): FailureAccrualPolicy =
    new FailureAccrualPolicy {
      def recordSuccess(): Unit = {
        self.recordSuccess()
        that.recordSuccess()
      }

      def markDeadOnFailure(): Option[Duration] = {
        val resSelf = self.markDeadOnFailure()
        val resThat = that.markDeadOnFailure()

        (resSelf, resThat) match {
          case (Some(_), None) => resSelf
          case (None, Some(_)) => resThat
          case (Some(durationSelf), Some(durationThat)) =>
            // max duration is chosen because generally FailureAccrual policies with longer backoffs
            // will be less sensitive, so it makes sense to respect that longer backoff
            Some(durationSelf.max(durationThat))
          case _ => None
        }
      }

      def revived(): Unit = {
        self.revived()
        that.revived()
      }
    }
}

object FailureAccrualPolicy {

  val DefaultConsecutiveFailures = 5
  val DefaultMinimumRequestThreshold = 5

  // These values approximate 5 consecutive failures at around 5k QPS during the
  // given window for randomly distributed failures. For lower (more typical)
  // workloads, this approach will better deal with randomly distributed failures.
  // Note that if an endpoint begins to fail all requests, FailureAccrual will
  // trigger in (window) * (1 - threshold) seconds - 6 seconds for these values.
  val DefaultSuccessRateThreshold = 0.8
  val DefaultSuccessRateWindow = 30.seconds

  private[this] val Success = 1
  private[this] val Failure = 0

  protected[this] val constantBackoff = Backoff.const(300.seconds)

  /**
   * A policy based on an exponentially-weighted moving average success rate
   * over a window. The window can represent a number of requests or a time
   * period, for example. A moving average is used so the success rate
   * calculation is biased towards more recent data points.
   *
   * If the computed weighted success rate is less
   * than the required success rate, `markDeadOnFailure()` will return
   * Some(Duration).
   *
   * @see com.twitter.finagle.util.Ema for how the success rate is computed
   *
   * @param requiredSuccessRate successRate that must be met
   *
   * @param window window over which the success rate is tracked.
   * `markDeadOnFailure()` will return None, until at least `window` data points
   * are provided.
   *
   * @param markDeadFor stream of durations to use for the next duration
   * returned from `markDeadOnFailure()`
   */
  private[this] abstract class SuccessRateFailureAccrualPolicy(
    requiredSuccessRate: Double,
    window: Long,
    markDeadFor: Stream[Duration]
  ) extends FailureAccrualPolicy {
    assert(
      requiredSuccessRate >= 0.0 && requiredSuccessRate <= 1.0,
      s"requiredSuccessRate must be [0, 1]: $requiredSuccessRate"
    )
    assert(window > 0, s"window must be positive: $window")

    // Pad the back of the stream to mark dead for a constant amount (300 seconds)
    // when the stream runs out.
    private[this] val freshMarkDeadFor = markDeadFor ++ constantBackoff

    // The head of `nextMarkDeadFor` is the next duration that will be returned
    // from `markDeadOnFailure()` if the required success rate is not met.
    // The tail is the remainder of the durations.
    private[this] var nextMarkDeadFor = freshMarkDeadFor

    private[this] val successRate = new Ema(window)

    override def recordSuccess(): Unit = synchronized {
      successRate.update(emaStamp, Success)
    }

    override def markDeadOnFailure(): Option[Duration] = synchronized {
      val emaStampForRequest = emaStamp
      val sr = successRate.update(emaStampForRequest, Failure)
      if (canRemove(emaStampForRequest, sr)) {
        val duration = nextMarkDeadFor.head
        nextMarkDeadFor = nextMarkDeadFor.tail
        Some(duration)
      } else {
        None
      }
    }

    override def revived: Unit = synchronized {
      nextMarkDeadFor = freshMarkDeadFor
      resetEmaCounter()
      successRate.reset()
    }

    /**
     * Returns the Ema stamp corresponding this request.
     *
     * Calls to this method must be synchronized by the caller.
     */
    protected def emaStamp: Long

    /**
     * Resets the counter used to track Ema data points.
     *
     * Calls to this method must be synchronized by the caller.
     */
    protected def resetEmaCounter(): Unit

    /**
     * Used to ensure that enough requests have been received within a window before triggering
     * failure accrual.
     */
    protected def sufficientRequests: Boolean

    // We can trigger FailureAccrual if the `window` has passed, success rate is below
    // `requiredSuccessRate`, and we have `sufficientRequests`
    private[this] def canRemove(emaStampForRequest: Long, sr: Double): Boolean =
      emaStampForRequest >= window && sr < requiredSuccessRate && sufficientRequests

  }

  /**
   * Returns a policy based on an exponentially-weighted moving average success
   * rate over a window of requests. A moving average is used so the success rate
   * calculation is biased towards more recent requests; for an endpoint with
   * low traffic, the window will span a longer time period and early successes
   * and failures may not accurately reflect the current health.
   *
   * If the computed weighted success rate is less
   * than the required success rate, `markDeadOnFailure()` will return
   * Some(Duration).
   *
   * @see com.twitter.finagle.util.Ema for how the success rate is computed
   *
   * @param requiredSuccessRate successRate that must be met
   *
   * @param window window over which the success rate is tracked. `window` requests
   * must occur for `markDeadOnFailure()` to ever return Some(Duration)
   *
   * @param markDeadFor stream of durations to use for the next duration
   * returned from `markDeadOnFailure()`
   */
  def successRate(
    requiredSuccessRate: Double,
    window: Int,
    markDeadFor: Stream[Duration]
  ): FailureAccrualPolicy = {
    new SuccessRateFailureAccrualPolicy(requiredSuccessRate, window, markDeadFor) {
      private[this] var totalRequests = 0L

      override protected def emaStamp: Long = {
        totalRequests += 1
        totalRequests
      }

      override protected def resetEmaCounter(): Unit = {
        totalRequests = 0
      }

      // Since this FailureAccrualPolicy is based on number of requests, there are always sufficient
      // requests
      override protected def sufficientRequests: Boolean = true
    }
  }

  /**
   * Returns a policy based on an exponentially-weighted moving average success
   * rate over a time window. A moving average is used so the success rate
   * calculation is biased towards more recent requests.
   *
   * If the computed weighted success rate is less
   * than the required success rate, `markDeadOnFailure()` will return
   * Some(Duration).
   *
   * @see com.twitter.finagle.util.Ema for how the success rate is computed
   *
   * @param requiredSuccessRate successRate that must be met
   *
   * @param window window over which the success rate is tracked.
   * `markDeadOnFailure()` will return None, until we get requests for a duration
   * of at least `window`.
   *
   * @param markDeadFor stream of durations to use for the next duration
   * returned from `markDeadOnFailure()`
   *
   * @param minRequestThreshold minimum number of requests in the past `window`
   * for `markDeadOnFailure()` to return a duration
   */
  def successRateWithinDuration(
    requiredSuccessRate: Double,
    window: Duration,
    markDeadFor: Stream[Duration],
    minRequestThreshold: Int
  ): FailureAccrualPolicy = {
    assert(window.isFinite, s"window must be finite: $window")

    new SuccessRateFailureAccrualPolicy(requiredSuccessRate, window.inMilliseconds, markDeadFor) {

      // Tracks the number of requests in the past `window`. The window was chosen to have 5 slices
      // arbitrarily.
      private[this] val requestCounter: WindowedAdder =
        WindowedAdder(window.inMilliseconds, 5, Stopwatch.systemMillis)

      override protected def sufficientRequests: Boolean = requestCounter.sum >= minRequestThreshold

      // Time elapsed since this instance was built, or since the endpoint was revived.
      private[this] var timeElapsed: Stopwatch.Elapsed = Stopwatch.start()

      override protected def emaStamp: Long = {
        timeElapsed.apply().inMilliseconds
      }

      override protected def resetEmaCounter(): Unit = {
        timeElapsed = Stopwatch.start()
        requestCounter.reset()
      }

      override def recordSuccess(): Unit = {
        requestCounter.incr()
        super.recordSuccess()
      }

      override def markDeadOnFailure(): Option[Duration] = {
        requestCounter.incr()
        super.markDeadOnFailure()
      }

    }
  }

  /**
   * Returns a policy based on an exponentially-weighted moving average success
   * rate over a time window. A moving average is used so the success rate
   * calculation is biased towards more recent requests.
   *
   * If the computed weighted success rate is less
   * than the required success rate, `markDeadOnFailure()` will return
   * Some(Duration).
   *
   * @see com.twitter.finagle.util.Ema for how the success rate is computed
   *
   * @param requiredSuccessRate successRate that must be met
   *
   * @param window window over which the success rate is tracked.
   * `markDeadOnFailure()` will return None, until we get requests for a duration
   * of at least `window`.
   *
   * @param markDeadFor stream of durations to use for the next duration
   * returned from `markDeadOnFailure()`
   */
  def successRateWithinDuration(
    requiredSuccessRate: Double,
    window: Duration,
    markDeadFor: Stream[Duration]
  ): FailureAccrualPolicy =
    successRateWithinDuration(
      requiredSuccessRate,
      window,
      markDeadFor,
      DefaultMinimumRequestThreshold)

  /**
   * A policy based on a maximum number of consecutive failures. If `numFailures`
   * occur consecutively, `checkFailures()` will return a Some(Duration) to
   * mark an endpoint dead for.
   *
   * @param numFailures number of consecutive failures
   *
   * @param markDeadFor stream of durations to use for the next duration
   * returned from `markDeadOnFailure()`
   */
  def consecutiveFailures(
    numFailures: Int,
    markDeadFor: Stream[Duration]
  ): FailureAccrualPolicy = new FailureAccrualPolicy {

    // Pad the back of the stream to mark dead for a constant amount (300 seconds)
    // when the stream runs out.
    private[this] val freshMarkDeadFor = markDeadFor ++ constantBackoff

    // The head of `nextMarkDeadFor` is the next duration that will be returned
    // from `markDeadOnFailure()` if the required success rate is not met.
    // The tail is the remainder of the durations.
    private[this] var nextMarkDeadFor = freshMarkDeadFor

    private[this] var consecutiveFailures = 0L

    def recordSuccess(): Unit = synchronized {
      consecutiveFailures = 0
    }

    def markDeadOnFailure(): Option[Duration] = synchronized {
      consecutiveFailures += 1
      if (consecutiveFailures >= numFailures) {
        val duration = nextMarkDeadFor.head
        nextMarkDeadFor = nextMarkDeadFor.tail
        Some(duration)
      } else {
        None
      }
    }

    def revived(): Unit = synchronized {
      consecutiveFailures = 0
      nextMarkDeadFor = freshMarkDeadFor
    }
  }
}
