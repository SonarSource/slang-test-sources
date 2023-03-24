/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.multipart.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

public abstract class NettyApplicationRequest(
    call: ApplicationCall,
    override val coroutineContext: CoroutineContext,
    public val context: ChannelHandlerContext,
    private val requestBodyChannel: ByteReadChannel,
    protected val uri: String,
    internal val keepAlive: Boolean
) : BaseApplicationRequest(call), CoroutineScope {

    public final override val queryParameters: Parameters = object : Parameters {
        private val decoder = QueryStringDecoder(uri, HttpConstants.DEFAULT_CHARSET, true, 1024, true)
        override val caseInsensitiveName: Boolean get() = true
        override fun getAll(name: String) = decoder.parameters()[name]
        override fun names() = decoder.parameters().keys
        override fun entries() = decoder.parameters().entries
        override fun isEmpty() = decoder.parameters().isEmpty()
    }

    override val rawQueryParameters: Parameters by lazy(LazyThreadSafetyMode.NONE) {
        val queryStartIndex = uri.indexOf('?').takeIf { it != -1 } ?: return@lazy Parameters.Empty
        parseQueryString(uri, startIndex = queryStartIndex + 1, decode = false)
    }

    override val cookies: RequestCookies = NettyApplicationRequestCookies(this)

    override fun receiveChannel(): ByteReadChannel = requestBodyChannel

    protected abstract fun newDecoder(): HttpPostMultipartRequestDecoder

    public fun close() {}
}
