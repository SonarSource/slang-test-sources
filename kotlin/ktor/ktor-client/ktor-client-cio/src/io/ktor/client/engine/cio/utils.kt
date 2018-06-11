package io.ktor.client.engine.cio

import io.ktor.client.call.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*

internal suspend fun CIOHttpRequest.write(output: ByteWriteChannel) {
    val builder = RequestResponseBuilder()

    val contentLength = headers[HttpHeaders.ContentLength] ?: content.contentLength?.toString()
    val contentEncoding = headers[HttpHeaders.TransferEncoding]
    val responseEncoding = content.headers[HttpHeaders.TransferEncoding]
    val chunked = contentLength == null || responseEncoding == "chunked" || contentEncoding == "chunked"

    try {
        builder.requestLine(method, url.fullPath, HttpProtocolVersion.HTTP_1_1.toString())
        builder.headerLine("Host", url.hostWithPort)

        if (!headers.contains(HttpHeaders.UserAgent)) {
            builder.headerLine("User-Agent", "CIO/ktor")
        }

        headers.flattenForEach { name, value ->
            if (HttpHeaders.ContentLength == name) return@flattenForEach // set later
            if (HttpHeaders.ContentType == name) return@flattenForEach // set later
            builder.headerLine(name, value)
        }

        content.headers.flattenForEach { name, value ->
            if (HttpHeaders.ContentLength == name) return@flattenForEach // TODO: throw exception for unsafe header?
            if (HttpHeaders.ContentType == name) return@flattenForEach
            builder.headerLine(name, value)
        }

        val contentType = headers[HttpHeaders.ContentType] ?: content.contentType?.toString()

        contentLength?.let { builder.headerLine(HttpHeaders.ContentLength, it) }
        contentType?.let { builder.headerLine(HttpHeaders.ContentType, it) }

        if (chunked && contentEncoding == null && responseEncoding == null && content !is OutgoingContent.NoContent) {
            builder.headerLine(HttpHeaders.TransferEncoding, "chunked")
        }

        builder.emptyLine()
        output.writePacket(builder.build())
        output.flush()
    } finally {
        builder.release()
    }

    if (content is OutgoingContent.NoContent)
        return

    val chunkedJob: EncoderJob? = if (chunked) encodeChunked(output, Unconfined) else null
    val channel = chunkedJob?.channel ?: output

    try {
        when (content) {
            is OutgoingContent.NoContent -> return
            is OutgoingContent.ByteArrayContent -> channel.writeFully(content.bytes())
            is OutgoingContent.ReadChannelContent -> content.readFrom().joinTo(channel, closeOnEnd = false)
            is OutgoingContent.WriteChannelContent -> content.writeTo(channel)
            is OutgoingContent.ProtocolUpgrade -> UnsupportedContentTypeException(content)
        }
    } catch (cause: Throwable) {
        channel.close(cause)
        executionContext.completeExceptionally(cause)
    } finally {
        channel.flush()
        chunkedJob?.channel?.close()
        chunkedJob?.join()
        executionContext.complete(Unit)
    }
}
