package com.twitter.finagle.memcached.unit.util

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.Memcached.UsePartitioningMemcachedClientToggle
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.factory.TimeoutFactory
import com.twitter.finagle.filter.NackAdmissionFilter
import com.twitter.finagle.liveness.{FailureAccrualFactory, FailureAccrualPolicy}
import com.twitter.finagle.memcached.{Client, KetamaPartitionedClient, TwemcacheClient}
import com.twitter.finagle.pool.SingletonPool
import com.twitter.finagle.param.Stats
import com.twitter.finagle.service._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.toggle.flag
import com.twitter.util.{Await, Time}
import org.scalatest.FunSuite
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.mockito.MockitoSugar

class MemcachedTest
    extends FunSuite
    with MockitoSugar
    with Eventually
    with IntegrationPatience {

  protected def baseClient: Memcached.Client = Memcached.client

  test("Memcached.Client has expected stack and params") {
    val markDeadFor = Backoff.const(1.second)
    val failureAccrualPolicy = FailureAccrualPolicy.consecutiveFailures(20, markDeadFor)
    val client = baseClient
      .configured(FailureAccrualFactory.Param(() => failureAccrualPolicy))
      .configured(Transporter.ConnectTimeout(100.milliseconds))
      .configured(TimeoutFilter.Param(200.milliseconds))
      .configured(TimeoutFactory.Param(200.milliseconds))
      .configured(Memcached.param.EjectFailedHost(false))

    val stack = client.stack
    assert(stack.contains(FailureAccrualFactory.role))
    assert(stack.contains(SingletonPool.role))
    assert(!stack.contains(NackAdmissionFilter.role))

    val params = client.params

    val FailureAccrualFactory.Param.Configured(policy) = params[FailureAccrualFactory.Param]
    assert(policy() == failureAccrualPolicy)
    assert(markDeadFor.take(10).force === (0 until 10 map { _ =>
      1.second
    }))
    assert(params[Transporter.ConnectTimeout] == Transporter.ConnectTimeout(100.milliseconds))
    assert(params[Memcached.param.EjectFailedHost] == Memcached.param.EjectFailedHost(false))
    assert(params[FailFastFactory.FailFast] == FailFastFactory.FailFast(false))
  }

  test("Memcache.newPartitionedClient enables FactoryToService for old client") {
    val sr = new InMemoryStatsReceiver
    val client = baseClient
      .configured(
        FailureAccrualFactory
          .Param(() => FailureAccrualPolicy.consecutiveFailures(100, Backoff.const(1.seconds)))
      )
      .withStatsReceiver(sr)
      .newRichClient("memcache=127.0.0.1:12345")
    testFactoryToService(client, Seq("memcache", "live_nodes"), sr)
    client.close()
  }

  test("Memcache.newPartitionedClient enables FactoryToService for new client") {
    flag.overrides.let(UsePartitioningMemcachedClientToggle, 1.0) {
      val sr = new InMemoryStatsReceiver
      val client = baseClient
        .configured(
          FailureAccrualFactory
            .Param(() => FailureAccrualPolicy.consecutiveFailures(100, Backoff.const(1.seconds)))
        )
        .configured(Stats(sr))
        .newRichClient("memcache=127.0.0.1:12345")
      testFactoryToService(client, Seq("memcache", "partitioner", "live_nodes"), sr)
      client.close()
    }
  }

  private[this] def testFactoryToService(
    client: Client,
    stat: Seq[String],
    sr: InMemoryStatsReceiver
  ): Unit = {
    // wait until we have at least 1 node, or risk getting a ShardNotAvailable exception
    eventually {
      assert(sr.gauges(stat)() >= 1)
    }
    val numberRequests = 10
    Time.withCurrentTimeFrozen { _ =>
      (0 until numberRequests).foreach { _ =>
        intercept[Failure](Await.result(client.get("foo"), 3.seconds))
      }
      // Since FactoryToService is enabled, number of requeues should be
      // limited by leaky bucket until it exhausts retries, instead of
      // retrying 25 times on service acquisition.
      // number of requeues = maxRetriesPerReq * numRequests
      assert(sr.counters(Seq("memcache", "retries", "requeues")) > numberRequests)
    }
  }

  test("Use new client when destination is Name.Path") {
    val client = baseClient.newRichClient("/s/cache/foo")
    assert(client.isInstanceOf[TwemcacheClient]) // new client
    client.close()
  }

  test("Use new client when destination is Name.Bound") {
    val boundName = Name.bound((1 to 3).map(Address("localhost", _)): _*)
    val client = baseClient.newRichClient(boundName, "foo")
    assert(client.isInstanceOf[KetamaPartitionedClient]) // old client
    client.close()
  }

  test("Use new client with toggle even when destination is Name.bound") {
    flag.overrides.let(UsePartitioningMemcachedClientToggle, 1.0) {
      val boundName = Name.bound((1 to 3).map(Address("localhost", _)): _*)
      val client = baseClient.newRichClient(boundName, "foo")
      assert(client.isInstanceOf[TwemcacheClient]) // new client
      client.close()
    }
  }
}
