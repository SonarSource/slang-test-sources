/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.http

import io.ktor.util.*

/**
 * Hostname of current origin.
 *
 * It uses "localhost" for all platforms except js.
 */
public actual val URLBuilder.Companion.origin: String
    get() = when(PlatformUtils.platform) {
        Platform.Browser -> {
            js(
                """
                var origin = ""
                if (typeof window !== 'undefined') {
                  origin = window.location.origin 
                } else {
                  origin = self.location.origin 
                }
                origin && origin != "null" ? origin : "http://localhost"
                """
            ) as String
        }
        else -> "http://localhost"
    }
