/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import okhttp3.*
import java.time.*
import kotlin.test.*

class RequestTests : TestWithKtor() {

    override val server = embeddedServer(CIO, serverPort) {
        routing {
            get("/delay") {
                val delay = call.parameters["delay"]!!.toLong()
                delay(delay)
                call.respondText("OK")
            }
            post("/echo") {
                call.respondText(call.receiveText())
            }
        }
    }

    class LoggingInterceptor(private val oneShot: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            assertEquals(oneShot, request.body!!.isOneShot())
            return chain.proceed(request)
        }
    }

    @Test
    fun testOneShotBodyStream() = testWithEngine(OkHttp) {
        config {
            engine {
                addInterceptor(LoggingInterceptor(true))
            }
        }

        test { client ->
            val response = client.post("$testUrl/echo") {
                val channel = ByteReadChannel("test".toByteArray())
                setBody(channel)
            }.body<String>()
            assertEquals("test", response)
        }
    }

    @Test
    fun testOneShotBodyArray() = testWithEngine(OkHttp) {
        config {
            engine {
                addInterceptor(LoggingInterceptor(false))
            }
        }

        test { client ->
            val response = client.post("$testUrl/echo") {
                setBody("test")
            }.body<String>()
            assertEquals("test", response)
        }
    }

    @Test
    fun testReusingRequestBuilderOnMultipleClients() {
        val requestBuilder = HttpRequestBuilder()
        requestBuilder.url.takeFrom("$testUrl/delay?delay=500")

        val clientFail = HttpClient(OkHttp) {
            engine {
                config {
                    readTimeout(Duration.ofMillis(100)) // SocketTimeoutException
                }
            }
        }
        val clientSuccess = HttpClient(OkHttp) {
            engine {
                config {
                    readTimeout(Duration.ofMillis(1000)) // success
                }
            }
        }

        runBlocking {
            assertFailsWith<SocketTimeoutException> { clientFail.get(requestBuilder).body<HttpResponseData>() }

            val response = clientSuccess.get(requestBuilder).body<String>()
            assertEquals("OK", response)
        }
    }

    class CustomException : IllegalStateException()

    @Test
    fun testBodyPropagatesExceptionType() = testWithEngine(OkHttp) {
        test { client ->
            assertFailsWith<CustomException> {
                client.post("$testUrl/echo") {
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            throw CustomException()
                        }
                    })
                }.body<String>()
            }
        }
    }
}
