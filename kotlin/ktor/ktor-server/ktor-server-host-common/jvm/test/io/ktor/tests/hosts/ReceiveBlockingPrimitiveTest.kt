/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.hosts

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.*
import java.lang.reflect.*
import kotlin.concurrent.*
import kotlin.test.*

class ReceiveBlockingPrimitiveTest {
    @Test
    fun testBlockingPrimitiveUsuallyAllowed() {
        testOnThread { call ->
            call.receive<InputStream>().close()
        }
    }

    @Test
    fun testBlockingPrimitiveWorksOnRestrictedThread() {
        testOnThread { call ->
            markParkingProhibited()

            call.receive<InputStream>().close()
        }
    }

    private fun testOnThread(
        block: suspend (ApplicationCall) -> Unit
    ) {
        val result = CompletableDeferred<Unit>()
        val call = TestCall()

        thread {
            try {
                runBlocking {
                    block(call)
                }
                result.complete(Unit)
            } catch (cause: Throwable) {
                result.completeExceptionally(cause)
            }
        }

        try {
            runBlocking {
                result.await()
            }
        } finally {
            call.close()
        }
    }

    private class TestCall : BaseApplicationCall(Application(applicationEngineEnvironment {})) {
        init {
            application.receivePipeline.installDefaultTransformations()
        }

        override val request: BaseApplicationRequest = object : BaseApplicationRequest(this) {
            override val queryParameters: Parameters
                get() = TODO("Not yet implemented")
            override val rawQueryParameters: Parameters
                get() = TODO("Not yet implemented")
            override val headers: Headers
                get() = TODO("Not yet implemented")
            override val local: RequestConnectionPoint
                get() = object : RequestConnectionPoint {
                    override val scheme: String
                        get() = TODO("Not yet implemented")
                    override val version: String
                        get() = TODO("Not yet implemented")
                    @Deprecated("Use localPort or serverPort instead")
                    override val port: Int
                        get() = TODO("Not yet implemented")
                    override val localPort: Int
                        get() = TODO("Not yet implemented")
                    override val serverPort: Int
                        get() = TODO("Not yet implemented")
                    @Deprecated("Use localHost or serverHost instead")
                    override val host: String
                        get() = TODO("Not yet implemented")
                    override val localHost: String
                        get() = TODO("Not yet implemented")
                    override val serverHost: String
                        get() = TODO("Not yet implemented")
                    override val localAddress: String
                        get() = TODO("Not yet implemented")
                    override val uri: String
                        get() = "http://test-uri.ktor.io/"
                    override val method: HttpMethod
                        get() = TODO("Not yet implemented")
                    override val remoteHost: String
                        get() = TODO("Not yet implemented")
                    override val remotePort: Int
                        get() = TODO("Not yet implemented")
                    override val remoteAddress: String
                        get() = TODO("Not yet implemented")
                }
            override val cookies: RequestCookies
                get() = TODO("Not yet implemented")

            override fun receiveChannel(): ByteReadChannel = ByteReadChannel.Empty
        }

        override val response: BaseApplicationResponse
            get() = error("Shouldn't be invoked")

        fun close() {
            application.dispose()
        }
    }

    private val prohibitParkingFunction: Method? by lazy {
        Class.forName("io.ktor.utils.io.jvm.javaio.PollersKt")
            .getMethod("prohibitParking")
    }

    private fun markParkingProhibited() {
        prohibitParkingFunction?.invoke(null)
    }
}
