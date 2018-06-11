package io.ktor.server.netty.http1

import io.ktor.content.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.server.netty.*
import io.ktor.server.netty.cio.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import kotlinx.coroutines.experimental.io.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

internal class NettyHttp1ApplicationResponse(call: NettyApplicationCall,
                                             context: ChannelHandlerContext,
                                             engineContext: CoroutineContext,
                                             userContext: CoroutineContext,
                                             val protocol: HttpVersion)

    : NettyApplicationResponse(call, context, engineContext, userContext) {

    private var responseStatus: HttpResponseStatus = HttpResponseStatus.OK
    private val responseHeaders = io.netty.handler.codec.http.DefaultHttpHeaders()

    override fun setStatus(statusCode: HttpStatusCode) {
        val cached = responseStatusCache[statusCode.value]

        responseStatus = cached?.takeIf { cached.reasonPhrase() == statusCode.description }
                ?: HttpResponseStatus(statusCode.value, statusCode.description)
    }

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            if (responseMessageSent) {
                if (responseMessage.isCancelled) throw CancellationException("Call execution has been cancelled")
                throw UnsupportedOperationException("Headers can no longer be set because response was already completed")
            }
            responseHeaders.add(name, value)
        }

        override fun getEngineHeaderNames(): List<String> = responseHeaders.map { it.key }
        override fun getEngineHeaderValues(name: String): List<String> = responseHeaders.getAll(name) ?: emptyList()
    }

    override fun responseMessage(chunked: Boolean, last: Boolean): Any {
        val responseMessage = DefaultHttpResponse(protocol, responseStatus, responseHeaders)
        if (chunked) {
            setChunked(responseMessage)
        }
        return responseMessage
    }

    override fun responseMessage(chunked: Boolean, data: ByteArray): Any {
        val responseMessage = DefaultFullHttpResponse(
                protocol, responseStatus, Unpooled.wrappedBuffer(data), responseHeaders, EmptyHttpHeaders.INSTANCE
        )
        if (chunked) {
            setChunked(responseMessage)
        }
        return responseMessage
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        val nettyContext = context
        val nettyChannel = nettyContext.channel()
        val userAppContext = userContext + NettyDispatcher.CurrentContext(nettyContext)

        val bodyHandler = nettyContext.pipeline().get(RequestBodyHandler::class.java)
        val upgradedReadChannel = bodyHandler.upgrade()

        val upgradedWriteChannel = ByteChannel()
        sendResponse(chunked = false, content = upgradedWriteChannel)

        with(nettyChannel.pipeline()) {
            if (get(NettyHttp1Handler::class.java) != null) {
                remove(NettyHttp1Handler::class.java)
                addFirst(NettyDirectDecoder())
            } else {
                cancel()
                val cause = CancellationException("HTTP upgrade has been cancelled")
                upgradedWriteChannel.cancel(cause)
                bodyHandler.close()
                throw cause
            }
        }

        val job = upgrade.upgrade(upgradedReadChannel, upgradedWriteChannel, engineContext, userAppContext)

        job.invokeOnCompletion {
            upgradedWriteChannel.close()
            bodyHandler.close()
        }

        (call as NettyApplicationCall).responseWriteJob.join()
    }

    private fun setChunked(message: HttpResponse) {
        if (message.status().code() != HttpStatusCode.SwitchingProtocols.value) {
            HttpUtil.setTransferEncodingChunked(message, true)
        }
    }
}