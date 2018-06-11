package io.ktor.client.features.websocket

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*

suspend fun HttpClient.webSocketRawSession(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    block: HttpRequestBuilder.() -> Unit = {}
): ClientWebSocketSession = request {
    this.method = method
    url("ws", host, port, path)
    block()
}

suspend fun HttpClient.webSocketSession(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    block: HttpRequestBuilder.() -> Unit = {}
): DefaultClientWebSocketSession {
    val feature = feature(WebSockets) ?: error("WebSockets feature should be installed")
    val session = webSocketRawSession(method, host, port, path, block)
    val origin = DefaultWebSocketSessionImpl(session, feature.context)

    return DefaultClientWebSocketSession(session.call, origin)
}

suspend fun HttpClient.webSocketRaw(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend ClientWebSocketSession.() -> Unit
): Unit {
    val session = webSocketRawSession(method, host, port, path) {
        url.protocol = URLProtocol.WS
        request()
    }

    try {
        session.block()
    } catch (cause: Throwable) {
        session.close(cause)
    } finally {
        session.close()
    }
}

suspend fun HttpClient.webSocket(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend DefaultClientWebSocketSession.() -> Unit
): Unit {
    val session = webSocketSession(method, host, port, path) {
        url.protocol = URLProtocol.WS
        request()
    }

    try {
        session.block()
    } catch (cause: Throwable) {
        session.close(cause)
    } finally {
        session.close()
    }
}

suspend fun HttpClient.wsRaw(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend ClientWebSocketSession.() -> Unit
): Unit = webSocketRaw(method, host, port, path, request, block)

suspend fun HttpClient.wssRaw(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 443, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend ClientWebSocketSession.() -> Unit
): Unit = webSocketRaw(method, host, port, path, request = {
    url.protocol = URLProtocol.WSS

    request()
}, block = block)

suspend fun HttpClient.ws(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 80, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend DefaultClientWebSocketSession.() -> Unit
): Unit = webSocket(method, host, port, path, request, block)

suspend fun HttpClient.wss(
    method: HttpMethod = HttpMethod.Get, host: String = "localhost", port: Int = 443, path: String = "/",
    request: HttpRequestBuilder.() -> Unit = {}, block: suspend DefaultClientWebSocketSession.() -> Unit
): Unit = webSocket(method, host, port, path, request = {
    url.protocol = URLProtocol.WSS
    request()
}, block = block)
