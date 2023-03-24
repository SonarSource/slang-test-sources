/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.http

import io.ktor.http.*
import kotlin.test.*

class HttpStatusCodeTest {
    @Test
    fun HttpStatusCodeAll() {
        assertEquals(52, HttpStatusCode.allStatusCodes.size)
    }

    @Test
    fun HttpStatusCodeFromValue() {
        assertEquals(HttpStatusCode.NotFound, HttpStatusCode.fromValue(404))
    }

    @Test
    fun HttpStatusCodeConstructed() {
        assertEquals(HttpStatusCode.NotFound, HttpStatusCode(404, "Not Found"))
    }

    @Test
    fun HttpStatusCodeWithDescription() {
        assertEquals(HttpStatusCode.NotFound, HttpStatusCode.NotFound.description("Missing Resource"))
    }

    @Test
    fun HttpStatusCodeToString() {
        assertEquals("404 Not Found", HttpStatusCode.NotFound.toString())
    }
}
