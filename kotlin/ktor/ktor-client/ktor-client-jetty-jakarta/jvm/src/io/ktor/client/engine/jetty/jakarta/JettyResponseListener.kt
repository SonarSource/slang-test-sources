/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.jetty.jakarta

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpMethod
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.eclipse.jetty.http.*
import org.eclipse.jetty.http2.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.http2.frames.*
import org.eclipse.jetty.util.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import kotlin.coroutines.*

internal data class StatusWithHeaders(val statusCode: HttpStatusCode, val headers: Headers)

private data class JettyResponseChunk(val buffer: ByteBuffer, val callback: Callback)

internal class JettyResponseListener(
    private val request: HttpRequestData,
    private val session: HTTP2ClientSession,
    private val channel: ByteWriteChannel,
    private val callContext: CoroutineContext
) : Stream.Listener {
    private val headersBuilder: HeadersBuilder = HeadersBuilder()
    private val onHeadersReceived = CompletableDeferred<HttpStatusCode?>()
    private val backendChannel = Channel<JettyResponseChunk>(Channel.UNLIMITED)

    init {
        runResponseProcessing()
    }

    override fun onPush(stream: Stream, frame: PushPromiseFrame): Stream.Listener {
        stream.reset(ResetFrame(frame.promisedStreamId, ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP)
        return Ignore
    }

    override fun onIdleTimeout(stream: Stream, cause: Throwable): Boolean {
        channel.close(cause)
        return true
    }

    override fun onReset(stream: Stream, frame: ResetFrame) {
        val error = when (frame.error) {
            0 -> null
            ErrorCode.CANCEL_STREAM_ERROR.code -> ClosedChannelException()
            else -> {
                val code = ErrorCode.from(frame.error)
                IOException("Connection reset ${code?.name ?: "with unknown error code ${frame.error}"}")
            }
        }

        error?.let { backendChannel.close(it) }

        onHeadersReceived.complete(null)
    }

    override fun onData(stream: Stream, frame: DataFrame, callback: Callback) {
        val data = frame.data!!
        try {
            if (!backendChannel.trySend(JettyResponseChunk(data, callback)).isSuccess) {
                throw IOException("backendChannel.offer() failed")
            }

            if (frame.isEndStream) backendChannel.close()
        } catch (cause: Throwable) {
            backendChannel.close(cause)
            callback.failed(cause)
        }
    }

    override fun onFailure(stream: Stream?, error: Int, reason: String?, failure: Throwable?, callback: Callback?) {
        callback?.succeeded()

        val messagePrefix = reason ?: "HTTP/2 failure"
        val message = when (error) {
            0 -> messagePrefix
            else -> "$messagePrefix, code $error"
        }

        val cause = IOException(message, failure)
        backendChannel.close(cause)
        onHeadersReceived.completeExceptionally(cause)
    }

    override fun onHeaders(stream: Stream, frame: HeadersFrame) {
        frame.metaData.fields.forEach { field ->
            headersBuilder.append(field.name, field.value)
        }

        if (frame.isEndStream || request.method == HttpMethod.Head) {
            backendChannel.close()
        }

        onHeadersReceived.complete(
            (frame.metaData as? MetaData.Response)?.let {
                val (status, reason) = it.status to it.reason
                reason?.let { text -> HttpStatusCode(status, text) } ?: HttpStatusCode.fromValue(status)
            }
        )
    }

    suspend fun awaitHeaders(): StatusWithHeaders {
        val statusCode = onHeadersReceived.await() ?: throw IOException("Connection reset")
        return StatusWithHeaders(statusCode, headersBuilder.build())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runResponseProcessing() = GlobalScope.launch(callContext) {
        while (true) {
            val (buffer, callback) = backendChannel.receiveCatching().getOrNull() ?: break
            try {
                if (buffer.remaining() > 0) channel.writeFully(buffer)
                callback.succeeded()
            } catch (cause: ClosedWriteChannelException) {
                callback.failed(cause)
                session.endPoint.close()
                break
            } catch (cause: Throwable) {
                callback.failed(cause)
                session.endPoint.close()
                throw cause
            }
        }
    }.invokeOnCompletion { cause ->
        channel.close(cause)
        backendChannel.close()
        GlobalScope.launch {
            for ((_, callback) in backendChannel) {
                callback.succeeded()
            }
        }
    }

    companion object {
        private val Ignore = Stream.Listener.Adapter()
    }
}
