package io.ktor.client.engine

import io.ktor.http.*
import io.ktor.util.network.*

/**
 * A [proxy](https://ktor.io/docs/proxy.html) configuration.
 */
public expect class ProxyConfig

/**
 * A type of the configured proxy.
 */
public expect val ProxyConfig.type: ProxyType

/**
 * A [proxy](https://ktor.io/docs/proxy.html) type.
 */
@Suppress("NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING", "KDocMissingDocumentation")
public enum class ProxyType {
    SOCKS,
    HTTP,
    UNKNOWN
}

/**
 * Resolves a remote address of [ProxyConfig].
 *
 * This operation can block.
 */
public expect fun ProxyConfig.resolveAddress(): NetworkAddress

/**
 * A [ProxyConfig] factory.
 *
 * @see [io.ktor.client.engine.HttpClientEngineConfig.proxy]
 */
public expect object ProxyBuilder {
    /**
     * Creates an HTTP proxy from [url].
     */
    public fun http(url: Url): ProxyConfig

    /**
     * Creates a socks proxy from [host] and [port].
     */
    public fun socks(host: String, port: Int): ProxyConfig
}

/**
 * Creates an HTTP proxy from [urlString].
 */
public fun ProxyBuilder.http(urlString: String): ProxyConfig = http(Url(urlString))
