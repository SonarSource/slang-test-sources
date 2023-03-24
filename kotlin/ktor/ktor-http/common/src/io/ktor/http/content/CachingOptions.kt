/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.http.content

import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlin.native.concurrent.*

/**
 * Specifies caching properties for [OutgoingContent] such as `Cache-Control` or `Expires`.
 * @property cacheControl header
 * @property expires header
 */
public data class CachingOptions(val cacheControl: CacheControl? = null, val expires: GMTDate? = null)

/**
 * Specifies a key for the [CacheControl] extension property for [OutgoingContent].
 */
public val CachingProperty: AttributeKey<CachingOptions> = AttributeKey("Caching")

/**
 * Gets or sets the [CacheControl] instance as an extension property on this content.
 */
public var OutgoingContent.caching: CachingOptions?
    get() = getProperty(CachingProperty)
    set(value) = setProperty(CachingProperty, value)
