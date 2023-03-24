/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.resources

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.resources.serialization.*
import io.ktor.server.plugins.*
import io.ktor.server.testing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(InternalAPI::class)
inline fun <reified T> ApplicationTestBuilder.urlShouldBeHandled(resource: T, content: String? = null) {
    on("making get request to resource $resource") {
        val result = runBlocking {
            client.get(
                HttpRequestBuilder().apply {
                    href(ResourcesFormat(), resource, url)
                }
            )
        }
        it("should have a response with OK status") {
            assertEquals(HttpStatusCode.OK, result.status)
        }
        if (content != null) {
            it("should have a response with content '$content'") {
                runBlocking {
                    assertEquals(content, result.content.readRemaining().readText())
                }
            }
        }
    }
}

fun ApplicationTestBuilder.urlShouldBeUnhandled(url: String) {
    on("making post request to $url") {
        it("should not be handled") {
            runBlocking {
                assertFalse(client.post(url).status.isSuccess())
            }
        }
    }
}
