package io.ktor.server.netty.http1

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.server.netty.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.multipart.*
import kotlinx.coroutines.experimental.io.*

internal class NettyHttp1ApplicationRequest(call: ApplicationCall,
                                            context: ChannelHandlerContext,
                                            val httpRequest: HttpRequest,
                                            requestBodyChannel: ByteReadChannel)
    : NettyApplicationRequest(call, context, requestBodyChannel, httpRequest.uri(), HttpUtil.isKeepAlive(httpRequest)) {
    override val local = NettyConnectionPoint(httpRequest, context)
    override val headers: Headers = NettyApplicationRequestHeaders(httpRequest)
    override fun newDecoder(): HttpPostMultipartRequestDecoder {
        return HttpPostMultipartRequestDecoder(httpRequest)
    }
}