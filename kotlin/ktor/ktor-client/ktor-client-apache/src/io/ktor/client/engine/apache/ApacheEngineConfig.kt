package io.ktor.client.engine.apache

import io.ktor.client.engine.*
import org.apache.http.client.config.*
import org.apache.http.impl.nio.client.*

/**
 * Configuration for [Apache] implementation of [HttpClientEngineFactory].
 */
class ApacheEngineConfig : HttpClientEngineConfig() {
    /**
     * Whether or not, it will follow `Location` headers. `false` by default.
     * It uses the default number of redirects defined by Apache's HttpClient that is 50.
     */
    var followRedirects: Boolean = false

    /**
     * Max milliseconds between TCP packets - default 10 seconds.
     * A value of 0 represents infinite, while -1 represents system's default value.
     */
    var socketTimeout = 10_000

    /**
     * Max milliseconds to establish an HTTP connection - default 10 seconds.
     * A value of 0 represents infinite, while -1 represents system's default value.
     */
    var connectTimeout = 10_000

    /**
     * Max milliseconds for the connection manager to start a request - default 20 seconds.
     * A value of 0 represents infinite, while -1 represents system's default value.
     */
    var connectionRequestTimeout = 20_000

    /**
     * Custom processor for [RequestConfig.Builder].
     */
    var customRequest: (RequestConfig.Builder.() -> RequestConfig.Builder) = { this }
        private set

    /**
     * Custom processor for [HttpAsyncClientBuilder].
     */
    var customClient: (HttpAsyncClientBuilder.() -> HttpAsyncClientBuilder) = { this }
        private set

    /**
     * Customizes a [RequestConfig.Builder] in the specified [block].
     */
    fun customizeRequest(block: RequestConfig.Builder.() -> Unit) {
        val current = customRequest
        customRequest = { current(); block(); this }
    }

    /**
     * Customizes a [HttpAsyncClientBuilder] in the specified [block].
     */
    fun customizeClient(block: HttpAsyncClientBuilder.() -> Unit) {
        val current = customClient
        customClient = { current(); block(); this }
    }
}