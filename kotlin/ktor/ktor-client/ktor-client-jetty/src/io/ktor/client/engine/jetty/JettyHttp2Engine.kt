package io.ktor.client.engine.jetty

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import kotlinx.coroutines.experimental.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.util.ssl.*
import java.net.*


internal class JettyHttp2Engine(config: JettyEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher = config.dispatcher ?: HTTP_CLIENT_DEFAULT_DISPATCHER

    private val sslContextFactory: SslContextFactory = config.sslContextFactory

    private val jettyClient = HTTP2Client().apply {
        addBean(sslContextFactory)
        start()
    }

    override suspend fun execute(call: HttpClientCall, data: HttpRequestData): HttpEngineCall {
        val request = JettyHttpRequest(call, this, dispatcher, data)
        val response = request.execute()

        return HttpEngineCall(request, response)
    }

    internal suspend fun connect(host: String, port: Int): Session {
        return withPromise { promise ->
            jettyClient.connect(sslContextFactory, InetSocketAddress(host, port), Session.Listener.Adapter(), promise)
        }
    }

    override fun close() {
        jettyClient.stop()
    }
}
