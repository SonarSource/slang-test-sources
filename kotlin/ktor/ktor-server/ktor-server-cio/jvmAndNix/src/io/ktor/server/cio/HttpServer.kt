/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.cio

import io.ktor.http.cio.*
import io.ktor.network.sockets.*
import io.ktor.server.cio.backend.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

/**
 * Represents a server instance
 * @property rootServerJob server job - root for all jobs
 * @property acceptJob client connections accepting job
 * @property serverSocket a deferred server socket instance, could be completed with error if it failed to bind
 */
public class HttpServer(
    public val rootServerJob: Job,
    public val acceptJob: Job,
    public val serverSocket: Deferred<ServerSocket>
)

/**
 * HTTP server connector settings
 * @property host to listen to
 * @property port to listen to
 * @property connectionIdleTimeoutSeconds time to live for IDLE connections
 */
public data class HttpServerSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val connectionIdleTimeoutSeconds: Long = 45
)

/**
 * Start an http server with [settings] invoking [handler] for every request
 */
@Deprecated(
    "Use handler function with single request parameter from package io.ktor.server.cio.backend.",
    level = DeprecationLevel.ERROR
)
public fun CoroutineScope.httpServer(
    settings: HttpServerSettings,
    handler: suspend CoroutineScope.(
        request: Request,
        input: ByteReadChannel,
        output: ByteWriteChannel,
        upgraded: CompletableDeferred<Boolean>?
    ) -> Unit
): HttpServer {
    return httpServer(settings) { request ->
        handler(this, request, input, output, upgraded)
    }
}
