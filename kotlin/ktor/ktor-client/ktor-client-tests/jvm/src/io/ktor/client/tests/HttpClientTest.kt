/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.test.dispatcher.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.coroutines.*
import kotlin.test.*

@Suppress("KDocMissingDocumentation")
abstract class HttpClientTest(private val factory: HttpClientEngineFactory<*>) : TestWithKtor() {
    override val server: ApplicationEngine = embeddedServer(CIO, serverPort) {
        routing {
            get("/empty") {
                call.respondText("")
            }
            get("/hello") {
                call.respondText("hello")
            }
            post("/echo") {
                val text = call.receiveText()
                call.respondText(text)
            }
        }
    }

    @Test
    fun testWithNoParentJob() {
        val block = suspend {
            val client = HttpClient(factory)
            val statement = client.prepareGet("http://localhost:$serverPort/hello")
            assertEquals("hello", statement.execute().bodyAsText())
        }

        val latch = ArrayBlockingQueue<Result<Unit>>(1)

        block.startCoroutine(
            object : Continuation<Unit> {
                override val context: CoroutineContext
                    get() = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    latch.put(result)
                }
            }
        )

        latch.take().exceptionOrNull()?.let { throw it }
    }

    @Test
    fun configCopiesOldPluginsAndInterceptors() {
        val customPluginKey = AttributeKey<Boolean>("customPlugin")
        val anotherCustomPluginKey = AttributeKey<Boolean>("anotherCustomPlugin")

        val originalClient = HttpClient(factory) {
            useDefaultTransformers = false

            install(DefaultRequest) {
                port = serverPort
                url.path("empty")
            }
            install("customPlugin") {
                attributes.put(customPluginKey, true)
            }
        }

        // check everything was installed in original
        val originalRequest = runBlocking {
            originalClient.request(HttpRequestBuilder())
        }.request
        assertEquals("/empty", originalRequest.url.fullPath)

        assertTrue(originalClient.attributes.contains(customPluginKey), "no custom plugin installed")

        // create a new client, copying the original, with:
        // - a reconfigured DefaultRequest
        // - a new custom plugin
        val newClient = originalClient.config {
            install(DefaultRequest) {
                port = serverPort
                url.path("hello")
            }
            install("anotherCustomPlugin") {
                attributes.put(anotherCustomPluginKey, true)
            }
        }

        // check the custom plugin remained installed
        // and that we override the DefaultRequest
        val newRequest = runBlocking {
            newClient.request(HttpRequestBuilder())
        }.request
        assertEquals("/hello", newRequest.url.fullPath)

        assertTrue(newClient.attributes.contains(customPluginKey), "no custom plugin installed")

        // check the new custom plugin is there too
        assertTrue(newClient.attributes.contains(anotherCustomPluginKey), "no other custom plugin installed")
    }

    @Test
    fun testErrorInWritingPropagates() = testSuspend {
        val client = HttpClient(factory)
        val channel = ByteChannel(true)
        channel.writeAvailable("text".toByteArray())
        channel.close(SendException())
        assertFailsWith<SendException>("Error on write") {
            client.post("http://localhost:$serverPort/echo") {
                setBody(channel)
            }.body<String>()
        }
    }

    private class SendException : RuntimeException("Error on write")
}
