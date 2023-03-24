/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.pool.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlin.collections.MutableMap
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.coroutines.*

@OptIn(InternalAPI::class)
internal class WinHttpRequestProducer(
    private val request: WinHttpRequest,
    private val data: HttpRequestData
) {
    private val closed = atomic(false)
    private val chunked: Boolean = request.chunkedMode == WinHttpChunkedMode.Enabled && !data.isUpgradeRequest()

    fun getHeaders(): Map<String, String> {
        val headers = data.headersToMap()

        if (chunked) {
            headers[HttpHeaders.TransferEncoding] = "chunked"
        }

        return headers
    }

    suspend fun writeBody() {
        if (closed.value) return

        val requestBody = data.body.toByteChannel()
        if (requestBody != null) {
            val readBuffer = ByteArrayPool.borrow()
            try {
                if (chunked) {
                    writeChunkedBody(requestBody, readBuffer)
                } else {
                    writeRegularBody(requestBody, readBuffer)
                }
            } finally {
                ByteArrayPool.recycle(readBuffer)
            }
        }
    }

    private suspend fun writeChunkedBody(requestBody: ByteReadChannel, readBuffer: ByteArray) {
        while (true) {
            val readBytes = requestBody.readAvailable(readBuffer).takeIf { it > 0 } ?: break
            writeBodyChunk(readBuffer, readBytes)
        }
        chunkTerminator.usePinned { src ->
            request.writeData(src, chunkTerminator.size)
        }
    }

    private suspend fun writeBodyChunk(readBuffer: ByteArray, length: Int) {
        // Write chunk length
        val chunkStart = "${length.toString(16)}\r\n".toByteArray()
        chunkStart.usePinned { src ->
            request.writeData(src, chunkStart.size)
        }
        // Write chunk data
        readBuffer.usePinned { src ->
            request.writeData(src, length)
        }
        // Write chunk ending
        chunkEnd.usePinned { src ->
            request.writeData(src, chunkEnd.size)
        }
    }

    private suspend fun writeRegularBody(requestBody: ByteReadChannel, readBuffer: ByteArray) {
        while (true) {
            val readBytes = requestBody.readAvailable(readBuffer).takeIf { it > 0 } ?: break
            readBuffer.usePinned { src ->
                request.writeData(src, readBytes)
            }
        }
    }

    private fun HttpRequestData.headersToMap(): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()

        mergeHeaders(headers, body) { key, value ->
            result[key] = value
        }

        return result
    }

    private suspend fun OutgoingContent.toByteChannel(): ByteReadChannel? = when (this) {
        is OutgoingContent.ByteArrayContent -> ByteReadChannel(bytes())
        is OutgoingContent.WriteChannelContent -> GlobalScope.writer(coroutineContext) {
            writeTo(channel)
        }.channel
        is OutgoingContent.ReadChannelContent -> readFrom()
        is OutgoingContent.NoContent -> null
        else -> throw UnsupportedContentTypeException(this)
    }

    companion object {
        private val chunkEnd = "\r\n".toByteArray()
        private val chunkTerminator = "0\r\n\r\n".toByteArray()
    }
}
