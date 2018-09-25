package com.prisma.metrics

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import io.micrometer.core.instrument.{Counter, MeterRegistry, Tag, Timer}

import scala.concurrent.{ExecutionContext, Future}

case class CustomTag(name: String, recordingThreshold: Long = 0)

trait Metric {

  val name: String
  val customTags: Seq[CustomTag]

  protected def createTags(customTagValues: Seq[String]): java.util.List[Tag] = {
    import collection.JavaConverters._
    customTags
      .zip(customTagValues)
      .map(tagAndValue => Tag.of(tagAndValue._1.name, sanitizeValue(tagAndValue._2)))
      .asJava
  }

  private def sanitizeValue(tagValue: String) = tagValue.replace('~', '-').replace('@', '-').replace('$', '-')
}

/**
  * A simple counter metric. Useful for point-in-time measurements like requests/s.
  *
  * @param name The counter name.
  * @param customTags A collection of unique custom tag names that will be filled during recording time.
  */
case class CounterMetric(name: String, customTags: Seq[CustomTag], meterRegistry: MeterRegistry) extends Metric {
  // Counters allow custom tags per occurrence
  def inc(customTagValues: String*) = {
    counter(customTagValues).increment()
  }

  def incBy(delta: Long, customTagValues: String*) = {
    counter(customTagValues).increment(delta)
  }

  private def counter(customTagValues: Seq[String]): Counter = {
    meterRegistry.counter(name, createTags(customTagValues))
  }
}

/**
  * A metric recording a constant value until changed (like turning a valve to a certain position).
  * This is useful when long-living things like open socket connections or CPU/memory are measured.
  *
  * Gauges are tricky because they encapsulate a state over time. TODO: elaborate
  * Our statsd is configured to clear a gauge if it hasn't been flushed in the interval (== no metrics reported for gauge).
  *
  * @param name The name of the Gauge.
  * @param predefTags A collection of unique custom tag names with values (!) that will be used for this gauge.
  */
case class GaugeMetric(name: String, predefTags: Seq[(CustomTag, String)], meterRegistry: MeterRegistry) extends Metric {

  override val customTags = predefTags.map(_._1)
  private val tags        = createTags(predefTags.map(_._2))
  private val gauge       = meterRegistry.gauge(name, tags, new AtomicLong(0))

  def inc: Unit                 = add(1)
  def dec: Unit                 = add(-1)
  def add(delta: Long): Unit    = gauge.addAndGet(delta)
  def set(fixedVal: Long): Unit = gauge.getAndSet(fixedVal)
  def get: Long                 = gauge.get
}

/**
  * A metric recording a timing and emitting a single measurement to statsd.
  * Useful for timing databases, requests, ... you probably get it.
  *
  * @param name The name of the timer.
  * @param customTags A collection of unique custom tag names that will be filled during recording time.
  */
case class TimerMetric(name: String, customTags: Seq[CustomTag], meterRegistry: MeterRegistry) extends Metric {
  def timeFuture[T](customTagValues: String*)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val startTime = java.lang.System.currentTimeMillis
    val result    = f

    result.onComplete { _ =>
      record(java.lang.System.currentTimeMillis - startTime, customTagValues)
    }

    result
  }

  def time[T](customTagValues: String*)(f: => T): T = {
    val startTime = java.lang.System.currentTimeMillis
    val res       = f

    record(java.lang.System.currentTimeMillis - startTime, customTagValues)
    res
  }

  def record(timeMillis: Long, customTagValues: Seq[String] = Seq.empty): Unit = {
    timer(customTagValues).record(timeMillis, TimeUnit.MILLISECONDS)
  }

  private def timer(customTagValues: Seq[String]): Timer = {
    val tags = createTags(customTagValues)
    meterRegistry.timer(name, tags)
  }
}
