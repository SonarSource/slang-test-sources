/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine.internal

import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import platform.posix.*

internal actual fun availableProcessorsBridge(): Int = 1

internal actual val Dispatchers.IOBridge: CoroutineDispatcher get() = Default

internal actual fun printError(message: Any?) {
    fprintf(stderr, "%s", message?.toString())
}

internal actual fun configureShutdownUrl(environment: ApplicationEnvironment, pipeline: EnginePipeline) {
    environment.config.propertyOrNull("ktor.deployment.shutdown.url")?.getString()?.let { _ ->
        error("Shutdown url is not supported on native")
    }
}
