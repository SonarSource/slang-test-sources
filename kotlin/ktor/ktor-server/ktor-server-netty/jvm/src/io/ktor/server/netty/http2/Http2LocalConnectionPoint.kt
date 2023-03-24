/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty.http2

import io.ktor.http.*
import io.netty.handler.codec.http2.*
import java.net.*

internal class Http2LocalConnectionPoint(
    private val nettyHeaders: Http2Headers,
    private val localNetworkAddress: InetSocketAddress?,
    private val remoteNetworkAddress: InetSocketAddress?,
) : RequestConnectionPoint {
    override val method: HttpMethod = nettyHeaders.method()?.let { HttpMethod.parse(it.toString()) } ?: HttpMethod.Get

    override val scheme: String
        get() = nettyHeaders.scheme()?.toString() ?: "http"

    override val version: String
        get() = "HTTP/2"

    override val uri: String
        get() = nettyHeaders.path()?.toString() ?: "/"

    @Deprecated("Use localHost or serverHost instead")
    override val host: String
        get() = nettyHeaders.authority()?.toString()?.substringBefore(":") ?: "localhost"

    @Deprecated("Use localPort or serverPort instead")
    override val port: Int
        get() = nettyHeaders.authority()?.toString()
            ?.substringAfter(":", "")?.takeIf { it.isNotEmpty() }?.toInt()
            ?: localNetworkAddress?.port
            ?: 80

    override val localHost: String
        get() = localNetworkAddress?.let { it.hostName ?: it.hostString } ?: "localhost"
    override val serverHost: String
        get() = nettyHeaders.authority()
            ?.toString()
            ?.substringBefore(":")
            ?: localHost
    override val localAddress: String
        get() = localNetworkAddress?.hostString ?: "localhost"

    private val defaultPort
        get() = URLProtocol.createOrDefault(scheme).defaultPort
    override val localPort: Int
        get() = localNetworkAddress?.port ?: defaultPort
    override val serverPort: Int
        get() = nettyHeaders.authority()
            ?.toString()
            ?.substringAfter(":", defaultPort.toString())?.toInt()
            ?: localPort

    override val remoteHost: String
        get() = remoteNetworkAddress
            ?.let { it.hostName ?: it.address.hostAddress }
            ?: "unknown"
    override val remotePort: Int
        get() = remoteNetworkAddress?.port ?: 0
    override val remoteAddress: String
        get() = remoteNetworkAddress?.hostString ?: "unknown"
}
