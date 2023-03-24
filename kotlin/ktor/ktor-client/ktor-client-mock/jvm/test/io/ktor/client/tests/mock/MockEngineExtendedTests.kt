/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests.mock

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.test.*

class MockEngineExtendedTests {

    @Test
    fun testExecutionOrder() = runBlocking {
        val mockEngine = MockEngine.config {
            addHandler { respondOk("first") }
            addHandler { respondBadRequest() }
            addHandler { respondOk("third") }
        }

        val client = HttpClient(mockEngine) { expectSuccess = false }

        assertEquals("first", client.get {}.body())
        assertEquals("Bad Request", client.get {}.body())
        assertEquals("third", client.get {}.body())
    }

    @Test
    fun testReceivedRequest() = runBlocking {
        val mockEngine = MockEngine.config {
            addHandler { respondOk("first") }
            addHandler { respondOk("second") }
            addHandler { respondOk("third") }
        }.create() as MockEngine

        val client = HttpClient(mockEngine)

        val firstCall = client.request("http://127.0.0.1") {
            header("header", "first")
            setBody("body")
        }.request

        val secondCall = client.request("https://127.0.0.02") {
            header("header", "second")
            setBody("secured")
        }.request

        assertEquals(firstCall.url.fullUrl, "http://127.0.0.1")
        assertEquals(firstCall.headers["header"], "first")
//        assertEquals((firstCall.body as TextContent).text, "body")
        assertEquals(secondCall.url.fullUrl, "https://127.0.0.02")
        assertEquals(secondCall.headers["header"], "second")
//        assertEquals((secondCall.body as TextContent).text, "secured")
    }

    @Test
    fun testUnhandledRequest() = runBlocking {
        val mockEngine = MockEngine.config {
            addHandler { respondOk("text") }
            reuseHandlers = false
        }

        val client = HttpClient(mockEngine)

        runBlocking {
            client.get("/").body<String>()
        }

        val exception = assertFails {
            runBlocking {
                client.get("/unhandled").body<String>()
            }
        }

        assertEquals("Unhandled http://localhost/unhandled", exception.message)
    }

    private val Url.fullUrl: String get() = "${protocol.name}://$host"
}
