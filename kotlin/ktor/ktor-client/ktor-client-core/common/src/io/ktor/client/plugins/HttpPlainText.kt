/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.plugins

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlin.math.*

private val LOGGER = KtorSimpleLogger("io.ktor.client.plugins.HttpPlainText")

/**
 * [HttpClient] plugin that encodes [String] request bodies to [TextContent]
 * and processes the response body as [String].
 *
 * To configure charsets set following properties in [HttpPlainText.Config].
 */
public class HttpPlainText internal constructor(
    charsets: Set<Charset>,
    charsetQuality: Map<Charset, Float>,
    sendCharset: Charset?,
    private val responseCharsetFallback: Charset
) {
    private val requestCharset: Charset
    private val acceptCharsetHeader: String

    init {
        val withQuality = charsetQuality.toList().sortedByDescending { it.second }
        val withoutQuality = charsets.filter { !charsetQuality.containsKey(it) }.sortedBy { it.name }

        acceptCharsetHeader = buildString {
            withoutQuality.forEach {
                if (isNotEmpty()) append(",")
                append(it.name)
            }

            withQuality.forEach { (charset, quality) ->
                if (isNotEmpty()) append(",")

                check(quality in 0.0..1.0)

                val truncatedQuality = (100 * quality).roundToInt() / 100.0
                append("${charset.name};q=$truncatedQuality")
            }

            if (isEmpty()) {
                append(responseCharsetFallback.name)
            }
        }

        requestCharset = sendCharset
            ?: withoutQuality.firstOrNull() ?: withQuality.firstOrNull()?.first ?: Charsets.UTF_8
    }

    /**
     * Charset configuration for [HttpPlainText] plugin.
     */
    @KtorDsl
    public class Config {
        internal val charsets: MutableSet<Charset> = mutableSetOf()
        internal val charsetQuality: MutableMap<Charset, Float> = mutableMapOf()

        /**
         * Add [charset] to allowed list with selected [quality].
         */
        public fun register(charset: Charset, quality: Float? = null) {
            quality?.let { check(it in 0.0..1.0) }

            charsets.add(charset)

            if (quality == null) {
                charsetQuality.remove(charset)
            } else {
                charsetQuality[charset] = quality
            }
        }

        /**
         * Explicit [Charset] for sending content.
         *
         * Use first with the highest quality from [register] charset if null.
         */
        public var sendCharset: Charset? = null

        /**
         * Fallback charset for the response.
         * Use it if no charset specified.
         */
        public var responseCharsetFallback: Charset = Charsets.UTF_8
    }

    @Suppress("KDocMissingDocumentation")
    public companion object Plugin : HttpClientPlugin<Config, HttpPlainText> {
        override val key: AttributeKey<HttpPlainText> = AttributeKey("HttpPlainText")

        override fun prepare(block: Config.() -> Unit): HttpPlainText {
            val config = Config().apply(block)

            with(config) {
                return HttpPlainText(
                    charsets,
                    charsetQuality,
                    sendCharset,
                    responseCharsetFallback
                )
            }
        }

        override fun install(plugin: HttpPlainText, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Render) { content ->
                plugin.addCharsetHeaders(context)

                if (content !is String) return@intercept

                val contentType = context.contentType()
                if (contentType != null && contentType.contentType != ContentType.Text.Plain.contentType) {
                    return@intercept
                }

                proceedWith(plugin.wrapContent(context, content, contentType))
            }

            scope.responsePipeline.intercept(HttpResponsePipeline.Transform) { (info, body) ->
                if (info.type != String::class || body !is ByteReadChannel) return@intercept

                val bodyBytes = body.readRemaining()
                val content = plugin.read(context, bodyBytes)
                proceedWith(HttpResponseContainer(info, content))
            }
        }
    }

    private fun wrapContent(request: HttpRequestBuilder, content: String, requestContentType: ContentType?): Any {
        val contentType: ContentType = requestContentType ?: ContentType.Text.Plain
        val charset = requestContentType?.charset() ?: requestCharset

        LOGGER.trace("Sending request body to ${request.url} as text/plain with charset $charset")
        return TextContent(content, contentType.withCharset(charset))
    }

    internal fun read(call: HttpClientCall, body: Input): String {
        val actualCharset = call.response.charset() ?: responseCharsetFallback
        LOGGER.trace("Reading response body for ${call.request.url} as String with charset $actualCharset")
        return body.readText(charset = actualCharset)
    }

    internal fun addCharsetHeaders(context: HttpRequestBuilder) {
        if (context.headers[HttpHeaders.AcceptCharset] != null) return
        LOGGER.trace("Adding Accept-Charset=$acceptCharsetHeader to ${context.url}")
        context.headers[HttpHeaders.AcceptCharset] = acceptCharsetHeader
    }
}

/**
 * Configure client charsets.
 *
 * ```kotlin
 * val client = HttpClient {
 *     Charsets {
 *         register(Charsets.UTF_8)
 *         register(Charsets.ISO_8859_1, quality = 0.1)
 *     }
 * }
 * ```
 */
@Suppress("FunctionName")
public fun HttpClientConfig<*>.Charsets(block: HttpPlainText.Config.() -> Unit) {
    install(HttpPlainText, block)
}
