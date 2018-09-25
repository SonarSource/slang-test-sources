package com.twitter.finagle.server

import com.twitter.finagle._
import com.twitter.finagle.filter._
import com.twitter.finagle.param._
import com.twitter.finagle.service.{DeadlineFilter, ExpiringService, StatsFilter, TimeoutFilter}
import com.twitter.finagle.{StackTransformer, Stack}
import com.twitter.finagle.stats.ServerStatsReceiver
import com.twitter.finagle.tracing._
import com.twitter.jvm.Jvm
import scala.collection.immutable

object StackServer {
  private[this] lazy val newJvmFilter = new MkJvmFilter(Jvm())

  private[this] class JvmTracing[Req, Rep]
      extends Stack.Module1[param.Tracer, ServiceFactory[Req, Rep]] {
    def role: Stack.Role = Role.jvmTracing
    def description: String = "Server-side JVM tracing"
    def make(
      _tracer: param.Tracer,
      next: ServiceFactory[Req, Rep]
    ): ServiceFactory[Req, Rep] = {
      val tracer = _tracer.tracer
      if (tracer.isNull) next
      else newJvmFilter[Req, Rep].andThen(next)
    }
  }

  /**
   * Canonical Roles for each Server-related Stack modules.
   */
  object Role extends Stack.Role("StackServer") {
    val serverDestTracing: Stack.Role = Stack.Role("ServerDestTracing")
    val jvmTracing: Stack.Role = Stack.Role("JvmTracing")
    val preparer: Stack.Role = Stack.Role("preparer")
    val protoTracing: Stack.Role = Stack.Role("protoTracing")
  }

  /**
   * Creates a default finagle server [[com.twitter.finagle.Stack]].
   * The default stack can be configured via [[com.twitter.finagle.Stack.Param]]'s
   * in the finagle package object ([[com.twitter.finagle.param]]) and specific
   * params defined in the companion objects of the respective modules.
   *
   * @see [[com.twitter.finagle.tracing.ServerDestTracingProxy]]
   * @see [[com.twitter.finagle.service.TimeoutFilter]]
   * @see [[com.twitter.finagle.service.DeadlineFilter]]
   * @see [[com.twitter.finagle.filter.DtabStatsFilter]]
   * @see [[com.twitter.finagle.service.StatsFilter]]
   * @see [[com.twitter.finagle.filter.RequestSemaphoreFilter]]
   * @see [[com.twitter.finagle.filter.ExceptionSourceFilter]]
   * @see [[com.twitter.finagle.filter.MkJvmFilter]]
   * @see [[com.twitter.finagle.tracing.ServerTracingFilter]]
   * @see [[com.twitter.finagle.tracing.TraceInitializerFilter]]
   * @see [[com.twitter.finagle.filter.MonitorFilter]]
   * @see [[com.twitter.finagle.filter.ServerStatsFilter]]
   */
  def newStack[Req, Rep]: Stack[ServiceFactory[Req, Rep]] = {
    val stk = new StackBuilder[ServiceFactory[Req, Rep]](stack.nilStack[Req, Rep])

    // this goes near the listener so it is close to where the handling happens.
    stk.push(ThreadUsage.module)

    // `ExportSslUsage` exports the TLS parameter to the R* Registry
    stk.push(ExportSslUsage.module)

    // We want to start expiring services as close to their instantiation
    // as possible. By installing `ExpiringService` here, we are guaranteed
    // to wrap the server's dispatcher.
    stk.push(ExpiringService.server)
    stk.push(
      Role.serverDestTracing,
      (next: ServiceFactory[Req, Rep]) => new ServerDestTracingProxy[Req, Rep](next)
    )
    stk.push(TimeoutFilter.serverModule)
    // The DeadlineFilter is pushed before the stats filters so stats are
    // recorded for the request. If a server processing deadlines is set in
    // TimeoutFilter, the deadline will start from the current time, and
    // therefore not be expired if the request were to then pass through
    // DeadlineFilter. Thus, DeadlineFilter is pushed after TimeoutFilter.
    stk.push(DeadlineFilter.module)
    stk.push(DtabStatsFilter.module)
    // Admission Control filters are inserted before `StatsFilter` so that rejected
    // requests are counted. We may need to adjust how latency are recorded
    // to exclude Nack response from latency stats, CSL-2306.
    stk.push(ServerAdmissionControl.module)
    stk.push(ConcurrentRequestFilter.module)
    stk.push(StatsFilter.module)
    stk.push(MaskCancelFilter.module)
    stk.push(ExceptionSourceFilter.module)
    stk.push(new JvmTracing)
    stk.push(ServerStatsFilter.module)
    stk.push(Role.protoTracing, identity[ServiceFactory[Req, Rep]](_))
    stk.push(ServerTracingFilter.module)
    stk.push(Role.preparer, identity[ServiceFactory[Req, Rep]](_))
    // The TraceInitializerFilter must be pushed after most other modules so that
    // any Tracing produced by those modules is enclosed in the appropriate
    // span.
    stk.push(TraceInitializerFilter.serverModule)
    stk.push(MonitorFilter.serverModule)
    stk.result
  }

  /**
   * The default params used for StackServers.
   */
  val defaultParams: Stack.Params =
    Stack.Params.empty + Stats(ServerStatsReceiver)

  /**
   * A set of StackTransformers for transforming server stacks.
   */
  private[finagle] object DefaultTransformer {
    @volatile private var underlying = immutable.Queue.empty[StackTransformer]

    def append(transformer: StackTransformer): Unit =
      synchronized { underlying = underlying :+ transformer }

    def transformers: Seq[StackTransformer] =
      underlying
  }
}

/**
 * A [[com.twitter.finagle.Server]] that composes a
 * [[com.twitter.finagle.Stack]].
 *
 * @see [[ListeningServer]] for a template implementation that tracks session resources.
 */
trait StackServer[Req, Rep]
    extends StackBasedServer[Req, Rep]
    with Stack.Parameterized[StackServer[Req, Rep]]
    with Stack.Transformable[StackServer[Req, Rep]] {

  def transformed(t: Stack.Transformer): StackServer[Req, Rep] =
    withStack(t(stack))

  /** The current stack used in this StackServer. */
  def stack: Stack[ServiceFactory[Req, Rep]]

  /** The current parameter map used in this StackServer. */
  def params: Stack.Params

  /** A new StackServer with the provided Stack. */
  def withStack(stack: Stack[ServiceFactory[Req, Rep]]): StackServer[Req, Rep]

  def withParams(ps: Stack.Params): StackServer[Req, Rep]

  override def configured[P: Stack.Param](p: P): StackServer[Req, Rep]

  override def configured[P](psp: (P, Stack.Param[P])): StackServer[Req, Rep]

  override def configuredParams(params: Stack.Params): StackServer[Req, Rep]
}
