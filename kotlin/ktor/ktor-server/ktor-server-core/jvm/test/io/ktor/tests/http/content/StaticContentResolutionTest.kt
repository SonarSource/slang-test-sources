/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.http.content

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.http.content.*
import io.ktor.util.*
import kotlinx.coroutines.*
import java.net.*
import kotlin.test.*

class StaticContentResolutionTest {

    private val baseUrl = StaticContentResolutionTest::class.java.classLoader.getResource("testjar.jar")

    @OptIn(InternalAPI::class)
    @Test
    fun testResourceClasspathResourceWithDirectoryInsideJar() {
        val content = resourceClasspathResource(URL("jar:$baseUrl!/testdir"), "testdir") {
            ContentType.defaultForFileExtension(it.path.extension())
        }

        assertNull(content)
    }

    @OptIn(InternalAPI::class)
    @Test
    fun testResourceClasspathResourceWithFileInsideJar() {
        val content = resourceClasspathResource(URL("jar:$baseUrl!/testdir/testfile"), "testdir/testfile") {
            ContentType.defaultForFileExtension(it.path.extension())
        }

        assertNotNull(content)
        with(content) {
            val data = String(runBlocking { readFrom().toByteArray() })
            assertEquals("test\n", data)
        }
    }
}
