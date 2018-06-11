package io.ktor.client.engine.jetty

import io.ktor.cio.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.client.utils.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.http.HttpMethod
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import org.eclipse.jetty.http.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.frames.*
import java.io.*
import java.util.*


internal class JettyHttpRequest(
    override val call: HttpClientCall,
    private val client: JettyHttp2Engine,
    private val dispatcher: CoroutineDispatcher,
    requestData: HttpRequestData
) : HttpRequest {
    override val attributes: Attributes = Attributes()

    override val method: HttpMethod = requestData.method
    override val url: Url = requestData.url
    override val headers: Headers = requestData.headers

    override val executionContext: CompletableDeferred<Unit> = requestData.executionContext

    override val content: OutgoingContent = requestData.body as OutgoingContent

    internal suspend fun execute(): HttpResponse {
        val requestTime = Date()
        val session = client.connect(url.host, url.port).apply {
            this.settings(SettingsFrame(emptyMap(), true), org.eclipse.jetty.util.Callback.NOOP)
        }

        val headersFrame = prepareHeadersFrame(content)

        val bodyChannel = ByteChannel()
        val responseContext = CompletableDeferred<Unit>()
        val responseListener = JettyResponseListener(bodyChannel, dispatcher, responseContext)

        val jettyRequest = withPromise<Stream> { promise ->
            session.newStream(headersFrame, promise, responseListener)
        }.let { JettyHttp2Request(it) }

        sendRequestBody(jettyRequest, content)

        val (status, headers) = responseListener.awaitHeaders()
        val origin = Closeable { bodyChannel.close() }
        return JettyHttpResponse(call, status, headers, requestTime, responseContext, bodyChannel, origin)
    }


    private fun prepareHeadersFrame(content: OutgoingContent): HeadersFrame {
        val rawHeaders = HttpFields()

        headers.flattenForEach { name, value ->
            rawHeaders.add(name, value)
        }

        content.headers.flattenForEach { name, value ->
            rawHeaders.add(name, value)
        }

        val meta = MetaData.Request(
            method.value,
            url.protocol.name,
            HostPortHttpField("${url.host}:${url.port}"),
            url.fullPath,
            HttpVersion.HTTP_2,
            rawHeaders,
            Long.MIN_VALUE
        )

        return HeadersFrame(meta, null, content is OutgoingContent.NoContent)
    }

    private suspend fun sendRequestBody(request: JettyHttp2Request, content: OutgoingContent) {
        when (content) {
            is OutgoingContent.NoContent -> return
            is OutgoingContent.ByteArrayContent -> {
                request.write(ByteBuffer.wrap(content.bytes()))
                request.endBody()
            }
            is OutgoingContent.ReadChannelContent -> content.readFrom().writeResponse(request)
            is OutgoingContent.WriteChannelContent -> {
                val source = writer(dispatcher + executionContext) { content.writeTo(channel) }.channel
                source.writeResponse(request)
            }
            is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(content)
        }
    }

    private fun ByteReadChannel.writeResponse(request: JettyHttp2Request) = launch(dispatcher + executionContext) {
        val buffer = HttpClientDefaultPool.borrow()
        pass(buffer) { request.write(it) }
        HttpClientDefaultPool.recycle(buffer)
        request.endBody()
    }.invokeOnCompletion { cause ->
        if (cause != null) {
            executionContext.completeExceptionally(cause)
        } else {
            executionContext.complete(Unit)
        }
    }
}


