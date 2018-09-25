package com.twitter.finagle.loadbalancer.roundrobin

import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.{Address, NoBrokersAvailableException}
import com.twitter.finagle.loadbalancer.EndpointFactory
import com.twitter.util.{Activity, Var}

trait RoundRobinSuite {
  // number of servers
  val N: Int = 100
  // number of reqs
  val R: Int = 100000
  // tolerated variance
  val variance: Double = 0.0001 * R

  trait RRServiceFactory extends EndpointFactory[Unit, Int] {
    def remake() = {}
    def address = Address.Failed(new Exception)
    def meanLoad: Double
  }

  protected val noBrokers: NoBrokersAvailableException = new NoBrokersAvailableException

  def newBal(
    fs: Var[Vector[RRServiceFactory]],
    sr: StatsReceiver = NullStatsReceiver
  ): RoundRobinBalancer[Unit, Int] = new RoundRobinBalancer(
    Activity(fs.map(Activity.Ok(_))),
    statsReceiver = sr,
    emptyException = noBrokers,
    maxEffort = 1
  )

  def assertEven(fs: Vector[RRServiceFactory]): Unit = {
    val ml = fs.head.meanLoad
    for (f <- fs) {
      assert(
        math.abs(f.meanLoad - ml) < variance,
        "ml=%f; f.ml=%f; ε=%f".format(ml, f.meanLoad, variance)
      )
    }
  }
}
