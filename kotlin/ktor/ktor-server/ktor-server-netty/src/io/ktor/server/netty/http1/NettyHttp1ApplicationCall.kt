package io.ktor.server.netty.http1

import io.ktor.application.*
import io.ktor.server.netty.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import kotlinx.coroutines.experimental.io.*
import kotlin.coroutines.experimental.*

internal class NettyHttp1ApplicationCall(
        application: Application,
        context: ChannelHandlerContext,
        httpRequest: HttpRequest,
        requestBodyChannel: ByteReadChannel,
        engineContext: CoroutineContext,
        userContext: CoroutineContext
) : NettyApplicationCall(application, context, httpRequest) {

    override val request = NettyHttp1ApplicationRequest(this, context, httpRequest, requestBodyChannel)
    override val response = NettyHttp1ApplicationResponse(this, context, engineContext, userContext, httpRequest.protocolVersion())
}