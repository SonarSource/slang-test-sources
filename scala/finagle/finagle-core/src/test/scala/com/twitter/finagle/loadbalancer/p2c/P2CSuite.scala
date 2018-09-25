package com.twitter.finagle.loadbalancer.p2c

import com.twitter.finagle.loadbalancer.EndpointFactory
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.Rng
import com.twitter.finagle.{Address, NoBrokersAvailableException, ServiceFactory}
import com.twitter.util.{Activity, Var}

trait P2CSuite {
  // number of servers
  val N: Int = 100
  // number of reqs
  val R: Int = 100000
  // tolerated variance
  val ε: Double = 0.0001 * R

  class Clock extends (() => Long) {
    var time: Long = 0L
    def advance(tick: Long): Unit = { time += tick }
    def apply(): Long = time
  }

  trait P2CServiceFactory extends EndpointFactory[Unit, Int] {
    def remake() = {}
    def address = Address.Failed(new Exception)
    def meanLoad: Double
  }

  val noBrokers = new NoBrokersAvailableException

  def newBal(
    fs: Var[Vector[P2CServiceFactory]],
    sr: StatsReceiver = NullStatsReceiver,
    clock: (() => Long) = System.nanoTime
  ): ServiceFactory[Unit, Int] = new P2CLeastLoaded(
    Activity(fs.map(Activity.Ok(_))),
    maxEffort = 5,
    rng = Rng(12345L),
    statsReceiver = sr,
    emptyException = noBrokers
  )

  def assertEven(fs: Vector[P2CServiceFactory]): Unit = {
    val ml = fs.head.meanLoad
    for (f <- fs) {
      assert(math.abs(f.meanLoad - ml) < ε, "ml=%f; f.ml=%f; ε=%f".format(ml, f.meanLoad, ε))
    }
  }
}
