/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.tests.utils.tests

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import test.server.tests.counter

internal fun Application.multithreadedTest() {
    routing {
        route("multithreaded") {
            get {
                call.respondText(counter.incrementAndGet().toString())
            }
            static {
                resource("jarfile", "String.class", "java.lang")
            }
        }
    }
}
