/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.request

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.utils.io.*

/**
 * A client's request.
 * To learn how to handle incoming requests, see [Handling requests](https://ktor.io/docs/requests.html).
 * @see [ApplicationCall.request]
 * @see [io.ktor.server.response.ApplicationResponse]
 */
public interface ApplicationRequest {
    /**
     * An [ApplicationCall] instance this [ApplicationRequest] is attached to.
     */
    public val call: ApplicationCall

    /**
     * A pipeline for receiving content.
     */
    public val pipeline: ApplicationReceivePipeline

    /**
     * Provides access to decoded parameters of a URL query string.
     */
    public val queryParameters: Parameters

    /**
     * Provides access to parameters of a URL query string.
     */
    public val rawQueryParameters: Parameters

    /**
     * Provides access to headers for the current request.
     * You can also get access to specific headers using dedicated extension functions,
     * such as [acceptEncoding], [contentType], [cacheControl], and so on.
     */
    public val headers: Headers

    /**
     * Provides access to connection details such as a host name, port, scheme, etc.
     * To get information about a request passed through an HTTP proxy or a load balancer,
     * install the ForwardedHeaders/XForwardedHeader plugin and use the [origin] property.
     */
    public val local: RequestConnectionPoint

    /**
     * Provides access to cookies for this request.
     */
    public val cookies: RequestCookies

    /**
     * Receives a raw body payload as a channel.
     */
    public fun receiveChannel(): ByteReadChannel
}

/**
 * Internal helper function to encode raw parameters. Should not be used directly.
 */
public fun ApplicationRequest.encodeParameters(parameters: Parameters): Parameters {
    return ParametersBuilder().apply {
        rawQueryParameters.names().forEach { key ->
            val values = parameters.getAll(key)?.map { it.decodeURLQueryComponent(plusIsSpace = true) }.orEmpty()
            appendAll(key.decodeURLQueryComponent(), values)
        }
    }.build()
}
