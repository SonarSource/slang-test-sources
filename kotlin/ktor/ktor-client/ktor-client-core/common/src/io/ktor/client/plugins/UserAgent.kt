/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.plugins

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.logging.*

private val LOGGER = KtorSimpleLogger("io.ktor.client.plugins.UserAgent")

/**
 * A plugin that adds a `User-Agent` header to all requests.
 *
 * @property agent a `User-Agent` header value.
 */
public class UserAgent private constructor(public val agent: String) {

    @KtorDsl
    public class Config(public var agent: String = "Ktor http-client")

    public companion object Plugin : HttpClientPlugin<Config, UserAgent> {
        override val key: AttributeKey<UserAgent> = AttributeKey("UserAgent")

        override fun prepare(block: Config.() -> Unit): UserAgent = UserAgent(Config().apply(block).agent)

        override fun install(plugin: UserAgent, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                LOGGER.trace("Adding User-Agent header: ${plugin.agent} for ${context.url}")
                context.header(HttpHeaders.UserAgent, plugin.agent)
            }
        }
    }
}

/**
 * Installs the [UserAgent] plugin with a browser-like user agent.
 */
public fun HttpClientConfig<*>.BrowserUserAgent() {
    install(UserAgent) {
        agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Ubuntu Chromium/70.0.3538.77 Chrome/70.0.3538.77 Safari/537.36"
    }
}

/**
 * Installs the [UserAgent] plugin with a CURL user agent.
 */
public fun HttpClientConfig<*>.CurlUserAgent() {
    install(UserAgent) {
        agent = "curl/7.61.0"
    }
}
