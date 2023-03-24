// ktlint-disable filename
/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.util.*
import io.ktor.util.logging.*
import kotlin.coroutines.*

/**
 * Engine environment configuration builder
 */
public actual class ApplicationEngineEnvironmentBuilder {
    /**
     * Parent coroutine context for an application
     */
    public actual var parentCoroutineContext: CoroutineContext = EmptyCoroutineContext

    /**
     * Paths to wait for application reload
     */
    public actual var watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH)

    /**
     * Application logger
     */
    public actual var log: Logger = KtorSimpleLogger("Application")

    /**
     * Application config
     */
    public actual var config: ApplicationConfig = MapApplicationConfig()

    /**
     * Application connectors list
     */
    public actual val connectors: MutableList<EngineConnectorConfig> = mutableListOf()

    /**
     * Application modules
     */
    public actual val modules: MutableList<Application.() -> Unit> = mutableListOf()

    /**
     * Application's root path (prefix, context path in servlet container).
     */
    public actual var rootPath: String = ""

    /**
     * Development mode enabled.
     */
    public actual var developmentMode: Boolean = PlatformUtils.IS_DEVELOPMENT_MODE

    /**
     * Install application module
     */
    public actual fun module(body: Application.() -> Unit) {
        modules.add(body)
    }

    /**
     * Build an application engine environment
     */
    public actual fun build(builder: ApplicationEngineEnvironmentBuilder.() -> Unit): ApplicationEngineEnvironment {
        builder(this)
        return NativeApplicationEngineEnvironment(
            log,
            config,
            connectors,
            modules,
            parentCoroutineContext,
            rootPath,
            developmentMode
        )
    }
}
