package com.twitter.finagle.liveness

import com.twitter.conversions.time._
import com.twitter.util._
import com.twitter.finagle.service._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar

@RunWith(classOf[JUnitRunner])
class FailureAccrualPolicyTest extends FunSuite with MockitoSugar {

  val constantBackoff = Backoff.const(5.seconds)
  val expBackoff = Backoff.equalJittered(5.seconds, 60.seconds)
  val expBackoffList = expBackoff take 6

  test("Consecutive failures policy: fail on nth attempt") {
    val policy = FailureAccrualPolicy.consecutiveFailures(3, constantBackoff)

    // Failing three times should return true from 'onFailure' on third time
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Consecutive failures policy: failures reset to zero on revived()") {
    val policy = FailureAccrualPolicy.consecutiveFailures(3, constantBackoff)

    assert(policy.markDeadOnFailure() == None)

    policy.revived()

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Consecutive failures policy: failures reset to zero on success") {
    val policy = FailureAccrualPolicy.consecutiveFailures(3, constantBackoff)

    assert(policy.markDeadOnFailure() == None)

    policy.recordSuccess()

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Consecutive failures policy: markDeadOnFailure() iterates over markDeadFor") {
    val policy = FailureAccrualPolicy.consecutiveFailures(1, expBackoff)

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))
  }

  test(
    "Consecutive failures policy: markDeadOnFailure() returns Some(300.seconds) when stream runs out"
  ) {
    val policy = FailureAccrualPolicy.consecutiveFailures(1, expBackoffList)

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

    for (i <- 0 until 5) assert(policy.markDeadOnFailure() == Some(300.seconds))
  }

  test("Consecutive failures policy: markDeadFor resets on revived()") {
    val policy = FailureAccrualPolicy.consecutiveFailures(1, expBackoff)

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

    policy.revived()

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))
  }

  test("Success rate policy markDeadOnFailure() doesn't return Some(Duration) until min requests") {
    val policy = FailureAccrualPolicy.successRate(0.5, 5, constantBackoff)

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Success rate policy markDeadOnFailure() returns Some(Duration) when succes rate not met") {
    val policy = FailureAccrualPolicy.successRate(0.5, 100, constantBackoff)

    for (i <- 0 until 100) policy.recordSuccess()

    // With a window of 100, it will take 100*ln(2)+1 = 70 failures for the success
    // rate to drop below 0.5 (half-life)
    for (i <- 0 until 69) assert(policy.markDeadOnFailure() == None)

    // 70th failure should trigger markDeadOnFailure to return Some(_)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Success rate policy: markDeadOnFailure() iterates over markDeadFor") {
    val policy = FailureAccrualPolicy.successRate(1, 1, expBackoff)

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))
  }

  test("Success rate policy: markDeadOnFailure() returns 300 when stream runs out") {
    val policy = FailureAccrualPolicy.successRate(1, 1, expBackoffList)

    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

    for (i <- 0 until 5) assert(policy.markDeadOnFailure() == Some(300.seconds))
  }

  test("Sucess rate policy: markDeadFor resets on revived()") {
    val policy = FailureAccrualPolicy.successRate(1, 2, expBackoff)

    // At least 2 requests need to fail before markDeadOnFailure() returns Some(_)
    assert(policy.markDeadOnFailure() == None)
    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

    policy.revived()

    // The failures should've been reset. markDeadOnFailure() should return None
    // on the first failed request.
    assert(policy.markDeadOnFailure() == None)
    for (i <- 0 until expBackoffList.length)
      assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))
  }

  test(
    "Success rate within duration policy: markDeadOnFailure() returns Some(Duration) when success rate not met"
  ) {
    val successRateDuration = 30.seconds
    Time.withCurrentTimeFrozen { timeControl =>
      val policy =
        FailureAccrualPolicy.successRateWithinDuration(1, successRateDuration, expBackoffList, 0)

      assert(policy.markDeadOnFailure() == None)

      // Advance the time with 'successRateDuration'.
      // All markDeadOnFailure() calls should now return Some(Duration),
      // and should iterate over expBackoffList.
      timeControl.advance(successRateDuration)
      for (i <- 0 until expBackoffList.length)
        assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

      // Stream 'expBackoffList' ran out of values.
      // All markDeadOnFailure() calls should return Some(300.seconds).
      for (i <- 0 until 5) assert(policy.markDeadOnFailure() == Some(300.seconds))
    }
  }

  test("Success rate within duration policy: revived() resets failures") {
    val successRateDuration = 30.seconds
    Time.withCurrentTimeFrozen { timeControl =>
      val policy =
        FailureAccrualPolicy.successRateWithinDuration(1, successRateDuration, expBackoffList, 0)

      timeControl.advance(successRateDuration)
      for (i <- 0 until expBackoffList.length)
        assert(policy.markDeadOnFailure() == Some(expBackoffList(i)))

      policy.revived()

      // Make sure the failure status has been reset.
      // This will also be registered as the timestamp of the first request.
      assert(policy.markDeadOnFailure() == None)

      // One failure after 'successRateDuration' should mark the node dead again.
      timeControl.advance(successRateDuration)
      assert(!policy.markDeadOnFailure().isEmpty)
    }
  }

  test("Success rate within duration policy: fractional success rate") {
    val successRateDuration = 100.seconds
    Time.withCurrentTimeFrozen { timeControl =>
      val policy =
        FailureAccrualPolicy.successRateWithinDuration(0.5, successRateDuration, constantBackoff, 0)

      for (i <- 0 until 100) {
        timeControl.advance(1.second)
        policy.recordSuccess()
      }

      // With a window of 100 seconds, it will take 100 * ln(2) + 1 = 70 seconds of failures
      // for the success rate to drop below 0.5 (half-life).
      for (i <- 0 until 69) {
        timeControl.advance(1.second)
        assert(policy.markDeadOnFailure() == None)
      }

      // 70th failure should make markDeadOnFailure() return Some(_)
      timeControl.advance(1.second)
      assert(policy.markDeadOnFailure() == Some(5.seconds))
    }
  }

  test("Success rate within duration policy: respects rps threshold") {
    val successRateDuration = 30.seconds
    Time.withCurrentTimeFrozen { timeControl =>
      val policy =
        FailureAccrualPolicy.successRateWithinDuration(1, successRateDuration, expBackoffList, 5)

      timeControl.advance(30.seconds)

      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == Some(5.seconds))
    }
  }

  def hybridPolicy = FailureAccrualPolicy
    .consecutiveFailures(3, expBackoff)
    .orElse(
      FailureAccrualPolicy.successRateWithinDuration(
        requiredSuccessRate = 0.8,
        window = 30.seconds,
        markDeadFor = expBackoff,
        minRequestThreshold = 0
      )
    )

  test("Hybrid policy: fail on nth attempt") {
    val policy = hybridPolicy
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Hybrid policy: failures reset to zero on revived()") {
    val policy = hybridPolicy
    assert(policy.markDeadOnFailure() == None)

    policy.revived()

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Hybrid policy: failures reset to zero on success") {
    val policy = hybridPolicy
    assert(policy.markDeadOnFailure() == None)

    policy.recordSuccess()

    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == None)
    assert(policy.markDeadOnFailure() == Some(5.seconds))
  }

  test("Hybrid policy: uses windowed success rate as well as consecutive failure") {
    Time.withCurrentTimeFrozen { timeControl =>
      val policy = hybridPolicy

      for (i <- 0 until 15) {
        policy.recordSuccess()
        timeControl.advance(1.second)

        policy.markDeadOnFailure()
        timeControl.advance(1.second)
      }

      assert(policy.markDeadOnFailure() == Some(5.seconds))

      policy.revived()

      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == None)
      assert(policy.markDeadOnFailure() == Some(5.seconds))
    }
  }

  test("Hybrid policy: uses longest duration when markDeadOnFailure()") {
    val policy1 = FailureAccrualPolicy
      .consecutiveFailures(3, Backoff.const(5.seconds))
      .orElse(
        FailureAccrualPolicy.consecutiveFailures(3, Backoff.const(10.seconds))
      )

    assert(policy1.markDeadOnFailure() == None)
    assert(policy1.markDeadOnFailure() == None)
    assert(policy1.markDeadOnFailure() == Some(10.seconds))

    val policy2 = FailureAccrualPolicy
      .consecutiveFailures(3, Backoff.const(10.seconds))
      .orElse(
        FailureAccrualPolicy.consecutiveFailures(3, Backoff.const(5.seconds))
      )

    assert(policy2.markDeadOnFailure() == None)
    assert(policy2.markDeadOnFailure() == None)
    assert(policy2.markDeadOnFailure() == Some(10.seconds))
  }
}
