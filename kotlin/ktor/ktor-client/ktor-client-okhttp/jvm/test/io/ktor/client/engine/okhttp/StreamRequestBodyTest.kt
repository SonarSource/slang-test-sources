/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import okio.*
import kotlin.test.*

class StreamRequestBodyTest {

    @Test
    fun testChannelThrowException() {
        val body = StreamRequestBody(0) {
            error("Can't read body")
        }

        assertFailsWith<IOException> {
            body.writeTo(Buffer())
        }
    }
}
