/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.events.*
import io.ktor.events.EventDefinition
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.util.logging.*
import kotlinx.atomicfu.*
import kotlin.coroutines.*

public class NativeApplicationEngineEnvironment(
    override val log: Logger,
    override val config: ApplicationConfig,
    override val connectors: MutableList<EngineConnectorConfig>,
    private val modules: MutableList<Application.() -> Unit>,
    override val parentCoroutineContext: CoroutineContext,
    override val rootPath: String,
    override val developmentMode: Boolean
) : ApplicationEngineEnvironment {

    override val monitor: Events = Events()

    override val application: Application = Application(this)

    override fun start() {
        safeRiseEvent(ApplicationStarting, application)
        try {
            modules.forEach { application.it() }
        } catch (cause: Throwable) {
            log.error("Failed to start application.", cause)
            destroy(application)
            throw cause
        }
        safeRiseEvent(ApplicationStarted, application)
    }

    override fun stop() {
        destroy(application)
    }

    private fun destroy(application: Application) {
        safeRiseEvent(ApplicationStopping, application)
        try {
            application.dispose()
        } catch (e: Throwable) {
            log.error("Failed to destroy application instance.", e)
        }
        safeRiseEvent(ApplicationStopped, application)
    }

    private fun safeRiseEvent(event: EventDefinition<Application>, application: Application) {
        try {
            monitor.raise(event, application)
        } catch (cause: Throwable) {
            log.error("One or more of the handlers thrown an exception", cause)
        }
    }
}
