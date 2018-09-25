package com.twitter.finagle.memcached.unit

import com.twitter.concurrent.Broker
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.liveness.FailureAccrualPolicy
import com.twitter.finagle.memcached._
import com.twitter.finagle.service.{Backoff, ResponseClassifier}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.util.{Await, Awaitable, Future, MockTimer, Time}
import org.junit.runner.RunWith
import org.mockito.Mockito.{times, verify, when}
import org.mockito.Matchers
import org.mockito.Matchers._
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar

@RunWith(classOf[JUnitRunner])
class KetamaFailureAccrualFactoryTest extends FunSuite with MockitoSugar {

  val TimeOut = 15.seconds

  private def awaitResult[T](awaitable: Awaitable[T]): T = Await.result(awaitable, TimeOut)

  class Helper(
    ejectFailedHost: Boolean,
    serviceRep: Future[Int] = Future.exception(new Exception),
    underlyingStatus: Status = Status.Open
  ) {
    val underlyingService = mock[Service[Int, Int]]
    when(underlyingService.close(any[Time])) thenReturn Future.Done
    when(underlyingService.status) thenReturn underlyingStatus
    when(underlyingService(Matchers.anyInt)) thenReturn serviceRep

    val underlying = mock[ServiceFactory[Int, Int]]
    when(underlying.close(any[Time])) thenReturn Future.Done
    when(underlying.status) thenReturn underlyingStatus
    when(underlying()) thenReturn Future.value(underlyingService)

    val key = mock[KetamaClientKey]
    val broker = new Broker[NodeHealth]

    val timer = new MockTimer
    val label = "test"
    val factory =
      new KetamaFailureAccrualFactory[Int, Int](
        underlying = underlying,
        policy = FailureAccrualPolicy.consecutiveFailures(3, Backoff.const(10.seconds)),
        responseClassifier = ResponseClassifier.Default,
        timer = timer,
        statsReceiver = NullStatsReceiver,
        key = key,
        healthBroker = broker,
        ejectFailedHost = ejectFailedHost,
        label = label
      )

    val service = awaitResult(factory())
    verify(underlying)()
  }

  test("fail immediately after consecutive failures, revive after markDeadFor duration") {
    val h = new Helper(false)
    import h._

    Time.withCurrentTimeFrozen { timeControl =>
      intercept[Exception] {
        awaitResult(service(123))
      }
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(factory.isAvailable)
      assert(service.isAvailable)

      // triggers markDead
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(!factory.isAvailable)
      assert(!service.isAvailable)
      assert(broker.recv.sync().isDefined == false)

      // skips dispatch
      val failureAccrualEx = intercept[FailureAccrualException] {
        awaitResult(factory())
      }
      assert(failureAccrualEx.serviceName == label)
      verify(underlyingService, times(3))(123)

      timeControl.advance(10.seconds)
      timer.tick()

      // revives after duration
      assert(factory.isAvailable)
      assert(service.isAvailable)
      assert(broker.recv.sync().isDefined == false)

      when(underlyingService(123)) thenReturn Future.value(123)

      assert(awaitResult(service(123)) == 123)

      // failures # is reset to 0
      intercept[Exception] {
        awaitResult(service(456))
      }
      assert(factory.isAvailable)
      assert(service.isAvailable)
      verify(underlyingService, times(4))(123)
      verify(underlyingService, times(1))(456)
    }
  }

  test("busy state of the underlying serviceFactory does not trigger FailureAccrualException") {
    val h = new Helper(false, Future.exception(new Exception), Status.Busy)
    import h._

    Time.withCurrentTimeFrozen { timeControl =>
      intercept[Exception] {
        awaitResult(service(123))
      }
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(!factory.isAvailable)
      assert(!service.isAvailable)
      // still dispatches
      verify(underlyingService, times(2))(123)

      // triggers markDead by the 3rd failure
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(!factory.isAvailable)
      assert(!service.isAvailable)
      assert(broker.recv.sync().isDefined == false)

      // skips dispatch after consecutive failures
      intercept[FailureAccrualException] {
        awaitResult(factory())
      }
      verify(underlyingService, times(3))(123)
    }
  }

  test("eject and revive failed host when ejectFailedHost=true") {
    val h = new Helper(true)
    import h._

    Time.withCurrentTimeFrozen { timeControl =>
      intercept[Exception] {
        awaitResult(service(123))
      }
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(factory.isAvailable)
      assert(service.isAvailable)

      // triggers markDead
      intercept[Exception] {
        awaitResult(service(123))
      }
      assert(!factory.isAvailable)
      assert(!service.isAvailable)

      // ejects
      val recv = broker.recv.sync()
      assert(awaitResult(recv) == NodeMarkedDead(key))

      timeControl.advance(10.seconds)
      timer.tick()

      // Probing, not revived yet.
      assert(factory.isAvailable)
      assert(service.isAvailable)

      when(underlyingService(123)) thenReturn Future.value(321)
      awaitResult(service(123))

      // A good dispatch; revived
      assert(factory.isAvailable)
      assert(service.isAvailable)
      val recv2 = broker.recv.sync()
      assert(awaitResult(recv2) == NodeRevived(key))
    }
  }

  test("treat successful response and cancelled exceptions as success") {
    val successes =
      Seq(
        Future.value(123),
        Future.exception(new CancelledRequestException(new Exception)),
        Future.exception(new CancelledConnectionException(new Exception)),
        Future.exception(ChannelWriteException(new CancelledRequestException(new Exception))),
        Future.exception(ChannelWriteException(new CancelledConnectionException(new Exception)))
      )

    successes.foreach { rep =>
      val h = new Helper(false, rep)
      import h._

      def assertReponse(rep: Future[Int]): Unit = {
        if (awaitResult(rep.liftToTry).isReturn)
          assert(awaitResult(service(123)) == awaitResult(rep))
        else intercept[Exception](awaitResult(service(123)))
      }

      Time.withCurrentTimeFrozen { _ =>
        assertReponse(rep)
        assertReponse(rep)
        assert(factory.isAvailable)
        assert(service.isAvailable)

        // not trigger markDead
        assertReponse(rep)
        assert(factory.isAvailable)
        assert(service.isAvailable)
      }
    }
  }
}
