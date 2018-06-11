package io.ktor.client.engine.apache

import io.ktor.client.engine.*


@Deprecated("Use Apache instead", ReplaceWith("Apache"))
typealias ApacheBackend = Apache

/**
 * [HttpClientEngineFactory] using `org.apache.httpcomponents.httpasyncclient`
 * with the the associated configuration [ApacheEngineConfig].
 *
 * Supports HTTP/2 and HTTP/1.x requests.
 */
object Apache : HttpClientEngineFactory<ApacheEngineConfig> {
    override fun create(block: ApacheEngineConfig.() -> Unit): HttpClientEngine {
        val config = ApacheEngineConfig().apply(block)
        return ApacheEngine(config)
    }
}
