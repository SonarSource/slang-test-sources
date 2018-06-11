package io.ktor.routing

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.pipeline.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.*

/**
 * Root routing node for an [Application]
 * @param application is an instance of [Application] for this routing
 */
class Routing(val application: Application) : Route(parent = null, selector = RootRouteSelector) {
    private val tracers = mutableListOf<(RoutingResolveTrace) -> Unit>()

    fun trace(block: (RoutingResolveTrace) -> Unit) {
        tracers.add(block)
    }

    private suspend fun interceptor(context: PipelineContext<Unit, ApplicationCall>) {
        val resolveContext = RoutingResolveContext(this, context.call, tracers)
        val resolveResult = resolveContext.resolve()
        if (resolveResult is RoutingResolveResult.Success) {
            executeResult(context, resolveResult.route, resolveResult.parameters)
        }
    }

    private suspend fun executeResult(context: PipelineContext<Unit, ApplicationCall>, route: Route, parameters: Parameters) {
        val routingCallPipeline = route.buildPipeline()
        val receivePipeline = ApplicationReceivePipeline().apply {
            merge(context.call.request.pipeline)
            merge(routingCallPipeline.receivePipeline)
        }
        val responsePipeline = ApplicationSendPipeline().apply {
            merge(context.call.response.pipeline)
            merge(routingCallPipeline.sendPipeline)
        }
        val routingCall = RoutingApplicationCall(context.call, route, receivePipeline, responsePipeline, parameters)
        application.environment.monitor.raise(RoutingCallStarted, routingCall)
        try {
            routingCallPipeline.execute(routingCall)
        } finally {
            application.environment.monitor.raise(RoutingCallFinished, routingCall)
        }
    }

    /**
     * Installable feature for [Routing]
     */
    companion object Feature : ApplicationFeature<Application, Routing, Routing> {

        /**
         * Event definition for when a routing-based call processing starts
         */
        val RoutingCallStarted = EventDefinition<RoutingApplicationCall>()
        /**
         * Event definition for when a routing-based call processing finished
         */
        val RoutingCallFinished = EventDefinition<RoutingApplicationCall>()

        override val key: AttributeKey<Routing> = AttributeKey("Routing")

        override fun install(pipeline: Application, configure: Routing.() -> Unit): Routing {
            val routing = Routing(pipeline).apply(configure)
            pipeline.intercept(ApplicationCallPipeline.Call) { routing.interceptor(this) }
            return routing
        }
    }

    private object RootRouteSelector : RouteSelector(RouteSelectorEvaluation.qualityConstant) {
        override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation {
            throw UnsupportedOperationException("Root selector should not be evaluated")
        }

        override fun toString(): String = ""
    }
}

/**
 * Gets an [Application] for this [Route] by scanning the hierarchy to the root
 */
val Route.application: Application
    get() = when {
        this is Routing -> application
        else -> parent?.application ?: throw UnsupportedOperationException("Cannot retrieve application from unattached routing entry")
    }

/**
 * Gets or installs a [Routing] feature for the this [Application] and runs a [configuration] script on it
 */
@ContextDsl
fun Application.routing(configuration: Routing.() -> Unit) = featureOrNull(Routing)?.apply(configuration) ?: install(Routing, configuration)

