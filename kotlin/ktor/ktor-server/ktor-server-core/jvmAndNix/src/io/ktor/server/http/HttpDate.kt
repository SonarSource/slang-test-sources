/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.http

import io.ktor.http.*
import io.ktor.util.date.*

/**
 * Format epoch milliseconds as HTTP date (GMT)
 */
@Deprecated(
    "This will be removed in future releases.",
    ReplaceWith("GMTDate(this).toHttpDate()", "io.ktor.util.date.GMTDate"),
    DeprecationLevel.ERROR
)
public fun Long.toHttpDateString(): String = GMTDate(this).toHttpDate()
