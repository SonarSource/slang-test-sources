package io.ktor.server.netty.http1

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.cio.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.experimental.io.*
import kotlin.coroutines.experimental.*

@ChannelHandler.Sharable
internal class NettyHttp1Handler(private val enginePipeline: EnginePipeline,
                                 private val environment: ApplicationEngineEnvironment,
                                 private val callEventGroup: EventExecutorGroup,
                                 private val engineContext: CoroutineContext,
                                 private val userContext: CoroutineContext,
                                 private val requestQueue: NettyRequestQueue) : ChannelInboundHandlerAdapter() {
    private var configured = false
    private var skipEmpty = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            handleRequest(ctx, msg)
        } else if (msg is LastHttpContent && !msg.content().isReadable && skipEmpty) {
            skipEmpty = false
            msg.release()
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    private fun handleRequest(context: ChannelHandlerContext, message: HttpRequest) {
        context.channel().config().isAutoRead = false

        val requestBodyChannel = when {
            message is LastHttpContent && !message.content().isReadable -> EmptyByteReadChannel
            message.method() === HttpMethod.GET -> {
                skipEmpty = true
                EmptyByteReadChannel
            }
            else -> content(context, message)
        }

        val call = NettyHttp1ApplicationCall(environment.application, context, message, requestBodyChannel, engineContext, userContext)
        requestQueue.schedule(call)
//        context.fireChannelRead(call)
    }

    private fun content(context: ChannelHandlerContext, message: HttpRequest): ByteReadChannel {
        return when (message) {
            is HttpContent -> {
                val bodyHandler = context.pipeline().get(RequestBodyHandler::class.java)
                bodyHandler.newChannel().also { bodyHandler.channelRead(context, message) }
            }
            else -> {
                val bodyHandler = context.pipeline().get(RequestBodyHandler::class.java)
                bodyHandler.newChannel()
            }
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (!configured) {
            configured = true
            val requestBodyHandler = RequestBodyHandler(ctx, requestQueue)
            val responseWriter = NettyResponsePipeline(ctx, WriterEncapsulation.Http1, requestQueue)

            ctx.pipeline().apply {
                addLast(requestBodyHandler)
                addLast(callEventGroup, NettyApplicationCallHandler(userContext, enginePipeline))
            }

            responseWriter.ensureRunning()
//            ctx.startLoop(enginePipeline)
        }

        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (configured) {
            configured = false
            ctx.pipeline().apply {
//                remove(RequestBodyHandler::class.java)
                remove(NettyApplicationCallHandler::class.java)
            }

            requestQueue.cancel()
//            ctx.stopLoop()
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        requestQueue.cancel()
        ctx.close()
    }
}

