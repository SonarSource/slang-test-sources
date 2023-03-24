/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.cio

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.util.network.*
import io.ktor.utils.io.*

internal class CIOApplicationRequest(
    call: ApplicationCall,
    remoteAddress: NetworkAddress?,
    localAddress: NetworkAddress?,
    private val input: ByteReadChannel,
    private val request: Request
) : BaseApplicationRequest(call) {
    override val cookies: RequestCookies by lazy { RequestCookies(this) }

    override fun receiveChannel() = input

    override val headers: Headers = CIOHeaders(request.headers)

    override val queryParameters: Parameters by lazy { encodeParameters(rawQueryParameters) }

    override val rawQueryParameters: Parameters by lazy {
        val uri = request.uri.toString()
        val queryStartIndex = uri.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uri, startIndex = queryStartIndex + 1, decode = false)
    }

    override val local: RequestConnectionPoint = CIOConnectionPoint(
        remoteAddress,
        localAddress,
        request.version.toString(),
        request.uri.toString(),
        request.headers[HttpHeaders.Host]?.toString(),
        HttpMethod.parse(request.method.value)
    )

    internal fun release() {
        request.release()
    }
}

internal class CIOConnectionPoint(
    private val remoteNetworkAddress: NetworkAddress?,
    private val localNetworkAddress: NetworkAddress?,
    override val version: String,
    override val uri: String,
    private val hostHeaderValue: String?,
    override val method: HttpMethod
) : RequestConnectionPoint {
    override val scheme: String
        get() = "http"

    private val defaultPort = URLProtocol.createOrDefault(scheme).defaultPort

    @Deprecated("Use localPort or serverPort instead")
    override val host: String
        get() = localNetworkAddress?.hostname
            ?: hostHeaderValue?.substringBefore(":")
            ?: "localhost"

    @Deprecated("Use localPort or serverPort instead")
    override val port: Int
        get() = localNetworkAddress?.port
            ?: hostHeaderValue?.substringAfter(":", "80")?.toInt()
            ?: 80

    override val localPort: Int
        get() = localNetworkAddress?.port ?: defaultPort

    override val serverPort: Int
        get() = hostHeaderValue
            ?.substringAfter(":", defaultPort.toString())?.toInt()
            ?: localPort

    override val localHost: String
        get() = localNetworkAddress?.hostname ?: "localhost"

    override val serverHost: String
        get() = hostHeaderValue?.substringBefore(":") ?: localHost

    override val localAddress: String
        get() = localNetworkAddress?.address ?: "localhost"

    override val remoteHost: String
        get() = remoteNetworkAddress?.hostname ?: "unknown"

    override val remotePort: Int
        get() = remoteNetworkAddress?.port ?: 0

    override val remoteAddress: String
        get() = remoteNetworkAddress?.address ?: "unknown"
}
