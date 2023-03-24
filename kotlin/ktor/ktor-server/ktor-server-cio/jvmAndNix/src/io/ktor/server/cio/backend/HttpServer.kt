/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.cio.backend

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.server.cio.*
import io.ktor.server.cio.internal.WeakTimeoutQueue
import io.ktor.server.engine.*
import io.ktor.server.engine.internal.*
import io.ktor.util.*
import io.ktor.util.logging.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*

/**
 * Start an http server with [settings] invoking [handler] for every request
 */
@OptIn(InternalAPI::class)
public fun CoroutineScope.httpServer(
    settings: HttpServerSettings,
    handler: HttpRequestHandler
): HttpServer {
    val socket = CompletableDeferred<ServerSocket>()

    val serverLatch: CompletableJob = Job()

    val serverJob = launch(
        context = CoroutineName("server-root-${settings.port}"),
        start = CoroutineStart.UNDISPATCHED
    ) {
        serverLatch.join()
    }

    val selector = SelectorManager(coroutineContext)
    val timeout = WeakTimeoutQueue(
        settings.connectionIdleTimeoutSeconds * 1000L
    )

    val logger = KtorSimpleLogger(
        HttpServer::class.simpleName ?: HttpServer::class.qualifiedName ?: HttpServer::class.toString()
    )

    val acceptJob = launch(serverJob + CoroutineName("accept-${settings.port}")) {
        aSocket(selector).tcp().bind(settings.host, settings.port).use { server ->
            socket.complete(server)

            val exceptionHandler = coroutineContext[CoroutineExceptionHandler]
                ?: DefaultUncaughtExceptionHandler(logger)

            val connectionScope = CoroutineScope(
                coroutineContext +
                    SupervisorJob(serverJob) +
                    exceptionHandler +
                    CoroutineName("request")
            )

            try {
                while (true) {
                    val client: Socket = try {
                        server.accept()
                    } catch (cause: IOException) {
                        continue
                    }

                    val connection = ServerIncomingConnection(
                        client.openReadChannel(),
                        client.openWriteChannel(),
                        client.remoteAddress.toNetworkAddress(),
                        client.localAddress.toNetworkAddress()
                    )

                    val clientJob = connectionScope.startServerConnectionPipeline(
                        connection,
                        timeout,
                        handler
                    )

                    clientJob.invokeOnCompletion {
                        client.close()
                    }
                }
            } catch (closed: ClosedChannelException) {
                coroutineContext.cancel()
            } finally {
                server.close()
                server.awaitClosed()
                connectionScope.coroutineContext.cancel()
            }
        }
    }

    acceptJob.invokeOnCompletion { cause ->
        cause?.let { socket.completeExceptionally(it) }
        serverLatch.complete()
        timeout.process()
    }

    @OptIn(InternalCoroutinesApi::class) // TODO it's attach child?
    serverJob.invokeOnCompletion(onCancelling = true) {
        timeout.cancel()
    }
    serverJob.invokeOnCompletion {
        selector.close()
    }

    return HttpServer(serverJob, acceptJob, socket)
}
