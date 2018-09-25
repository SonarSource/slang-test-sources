package com.twitter.finagle.loadbalancer.aperture

import com.twitter.finagle.loadbalancer.{
  EndpointFactory,
  FailingEndpointFactory,
  PeakEwma,
  Updating
}
import com.twitter.finagle.{NoBrokersAvailableException, ServiceFactoryProxy}
import com.twitter.finagle.stats.{Counter, StatsReceiver}
import com.twitter.finagle.util.Rng
import com.twitter.finagle.{NoBrokersAvailableException, ServiceFactoryProxy}
import com.twitter.util.{Activity, Duration, Future, Timer, Time}

/**
 * Aperture (which is backed by the theory behind p2c) along with the [[PeakEwma]]
 * load metric.
 */
private[loadbalancer] final class AperturePeakEwma[Req, Rep](
  protected val endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
  protected val smoothWin: Duration,
  protected val decayTime: Duration,
  protected val nanoTime: () => Long,
  protected val lowLoad: Double,
  protected val highLoad: Double,
  protected val minAperture: Int,
  protected val maxEffort: Int,
  protected val rng: Rng,
  protected val statsReceiver: StatsReceiver,
  protected val label: String,
  protected val timer: Timer,
  protected val emptyException: NoBrokersAvailableException,
  protected val useDeterministicOrdering: Option[Boolean]
) extends Aperture[Req, Rep]
    with PeakEwma[Req, Rep]
    with LoadBand[Req, Rep]
    with Expiration[Req, Rep]
    with Updating[Req, Rep] {
  require(minAperture > 0, s"minAperture must be > 0, but was $minAperture")
  protected[this] val maxEffortExhausted: Counter = statsReceiver.counter("max_effort_exhausted")

  // We set the idle time as a function of the aperture's smooth window.
  // The aperture growth is dampened by this window so after X windows
  // have passed, we can be sufficiently confident that an idle session
  // is no longer needed. We choose a default of 10 for X which should
  // give us a high degree of confidence and, based on the default smooth
  // windows, should be on the order of minutes.
  protected val endpointIdleTime: Duration = smoothWin * 10

  private[this] val expiryTask = newExpiryTask(timer)

  case class Node(factory: EndpointFactory[Req, Rep])
      extends ServiceFactoryProxy[Req, Rep](factory)
      with PeakEwmaNode
      with LoadBandNode
      with ExpiringNode
      with ApertureNode

  protected def newNode(factory: EndpointFactory[Req, Rep]): Node = Node(factory)
  protected def failingNode(cause: Throwable): Node = Node(new FailingEndpointFactory(cause))

  override def close(deadline: Time): Future[Unit] = {
    expiryTask.cancel()
    super.close(deadline)
  }
}
