/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.util

/**
 * Length of this [LongRange]
 */
@Deprecated("Not supported anymore.", level = DeprecationLevel.ERROR)
public val LongRange.length: Long
    get() = (endInclusive - start + 1).coerceAtLeast(0L)

/**
 * Returns `true` if [other] range is fully contained inside [this] range
 */
public operator fun LongRange.contains(other: LongRange): Boolean =
    other.start >= start && other.endInclusive <= endInclusive
