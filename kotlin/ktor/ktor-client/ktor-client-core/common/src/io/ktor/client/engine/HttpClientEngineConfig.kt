/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.engine

import io.ktor.util.*

/**
 * Base configuration for [HttpClientEngine].
 */
@KtorDsl
public open class HttpClientEngineConfig {
    /**
     * Specifies network threads count advice.
     */
    public var threadsCount: Int = 4

    /**
     * Enables HTTP pipelining advice.
     */
    public var pipelining: Boolean = false

    /**
     * Specifies a proxy address to use.
     * Uses a system proxy by default.
     *
     * You can learn more from [Proxy](https://ktor.io/docs/proxy.html).
     */
    public var proxy: ProxyConfig? = null
}
