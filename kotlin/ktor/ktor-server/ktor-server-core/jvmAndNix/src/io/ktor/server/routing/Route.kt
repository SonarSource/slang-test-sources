/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.routing

import io.ktor.server.application.*
import io.ktor.util.*
import io.ktor.util.collections.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.concurrent.*

/**
 * Describes a node in a routing tree.
 * @see [Application.routing]
 *
 * @param parent is a parent node in the tree, or null for root node.
 * @param selector is an instance of [RouteSelector] for this node.
 * @param developmentMode is flag to switch report level for stack traces.
 */
@Suppress("DEPRECATION")
@KtorDsl
public open class Route(
    public val parent: Route?,
    public val selector: RouteSelector,
    developmentMode: Boolean = false,
    environment: ApplicationEnvironment? = null
) : ApplicationCallPipeline(developmentMode, environment) {

    /**
     * Describes a node in a routing tree.
     *
     * @param parent is a parent node in the tree, or null for root node.
     * @param selector is an instance of [RouteSelector] for this node.
     */
    @Deprecated(message = "Please use constructor with developmentMode parameter", level = DeprecationLevel.HIDDEN)
    public constructor(
        parent: Route?,
        selector: RouteSelector,
        environment: ApplicationEnvironment? = null
    ) : this(parent, selector, developmentMode = false, environment = environment)

    /**
     * List of child routes for this node.
     */
    public val children: List<Route> get() = childList

    @OptIn(InternalAPI::class)
    private val childList: MutableList<Route> = mutableListOf()

    private var cachedPipeline: ApplicationCallPipeline? = null

    @OptIn(InternalAPI::class)
    internal val handlers = mutableListOf<PipelineInterceptor<Unit, ApplicationCall>>()

    /**
     * Creates a child node in this node with a given [selector] or returns an existing one with the same selector.
     */
    public fun createChild(selector: RouteSelector): Route {
        val existingEntry = childList.firstOrNull { it.selector == selector }
        if (existingEntry == null) {
            val entry = Route(this, selector, developmentMode, environment)
            childList.add(entry)
            return entry
        }
        return existingEntry
    }

    /**
     * Allows using a route instance for building additional routes.
     */
    public operator fun invoke(body: Route.() -> Unit): Unit = body()

    /**
     * Installs a handler into this route which is called when the route is selected for a call.
     */
    public fun handle(handler: PipelineInterceptor<Unit, ApplicationCall>) {
        handlers.add(handler)

        // Adding a handler invalidates only pipeline for this entry
        cachedPipeline = null
    }

    override fun afterIntercepted() {
        // Adding an interceptor invalidates pipelines for all children
        // We don't need synchronisation here, because order of intercepting and acquiring pipeline is indeterminate
        // If some child already cached its pipeline, it's ok to execute with outdated pipeline
        invalidateCachesRecursively()
    }

    private fun invalidateCachesRecursively() {
        cachedPipeline = null
        childList.forEach { it.invalidateCachesRecursively() }
    }

    internal fun buildPipeline(): ApplicationCallPipeline = cachedPipeline ?: run {
        var current: Route? = this
        val pipeline = ApplicationCallPipeline(developmentMode, application.environment)
        val routePipelines = mutableListOf<ApplicationCallPipeline>()
        while (current != null) {
            routePipelines.add(current)
            current = current.parent
        }

        for (index in routePipelines.lastIndex downTo 0) {
            val routePipeline = routePipelines[index]
            pipeline.merge(routePipeline)
            pipeline.receivePipeline.merge(routePipeline.receivePipeline)
            pipeline.sendPipeline.merge(routePipeline.sendPipeline)
        }

        val handlers = handlers
        for (index in 0..handlers.lastIndex) {
            pipeline.intercept(Call) {
                if (call.isHandled) return@intercept
                handlers[index].invoke(this, Unit)
            }
        }
        cachedPipeline = pipeline
        pipeline
    }

    override fun toString(): String {
        return when (val parentRoute = parent?.toString()) {
            null -> when (selector) {
                is TrailingSlashRouteSelector -> "/"
                else -> "/$selector"
            }
            else -> when (selector) {
                is TrailingSlashRouteSelector -> if (parentRoute.endsWith('/')) parentRoute else "$parentRoute/"
                else -> if (parentRoute.endsWith('/')) "$parentRoute$selector" else "$parentRoute/$selector"
            }
        }
    }
}

/**
 * Return list of endpoints with handlers under this route.
 */
public fun Route.getAllRoutes(): List<Route> {
    val endpoints = mutableListOf<Route>()
    getAllRoutes(endpoints)
    return endpoints
}

private fun Route.getAllRoutes(endpoints: MutableList<Route>) {
    if (handlers.isNotEmpty()) {
        endpoints.add(this)
    }
    children.forEach { it.getAllRoutes(endpoints) }
}
