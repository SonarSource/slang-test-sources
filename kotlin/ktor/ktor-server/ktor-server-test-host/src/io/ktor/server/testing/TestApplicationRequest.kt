package io.ktor.server.testing

import io.ktor.application.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.server.engine.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.io.*

class TestApplicationRequest(
        call: ApplicationCall,
        var method: HttpMethod = HttpMethod.Get,
        var uri: String = "/",
        var version: String = "HTTP/1.1"
) : BaseApplicationRequest(call) {
    var protocol: String = "http"

    override val local = object : RequestConnectionPoint {
        override val uri: String
            get() = this@TestApplicationRequest.uri

        override val method: HttpMethod
            get() = this@TestApplicationRequest.method

        override val scheme: String
            get() = protocol

        override val port: Int
            get() = header(HttpHeaders.Host)?.substringAfter(":", "80")?.toInt() ?: 80

        override val host: String
            get() = header(HttpHeaders.Host)?.substringBefore(":") ?: "localhost"

        override val remoteHost: String
            get() = "localhost"

        override val version: String
            get() = this@TestApplicationRequest.version
    }

    @Volatile
    var bodyChannel: ByteReadChannel = EmptyByteReadChannel

    @Deprecated("Use setBody() method instead", ReplaceWith("setBody()"))
    var bodyBytes: ByteArray
        @Deprecated("TestApplicationEngine no longer supports bodyBytes.get()", level = DeprecationLevel.ERROR)
        get() = error("TestApplicationEngine no longer supports bodyBytes.get()")
        set(value) { setBody(value) }

    @Deprecated("Use setBody() method instead", ReplaceWith("setBody()"))
    var body: String
        @Deprecated("TestApplicationEngine no longer supports body.get()", level = DeprecationLevel.ERROR)
        get() = error("TestApplicationEngine no longer supports body.get()")
        set(value) { setBody(value) }

    @Deprecated(
            message = "multiPartEntries is deprecated, use setBody() method instead",
            replaceWith = ReplaceWith("setBody()"), level = DeprecationLevel.ERROR
    )
    var multiPartEntries: List<PartData> = listOf()

    override val queryParameters by lazy(LazyThreadSafetyMode.NONE) { parseQueryString(queryString()) }

    override val cookies = RequestCookies(this)

    private var headersMap: MutableMap<String, MutableList<String>>? = hashMapOf()

    fun addHeader(name: String, value: String) {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        map.getOrPut(name, { arrayListOf() }).add(value)
    }

    override val headers: Headers by lazy(LazyThreadSafetyMode.NONE) {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        headersMap = null
        Headers.build {
            map.forEach { (name, values) ->
                appendAll(name, values)
            }
        }
    }

    override fun receiveChannel(): ByteReadChannel = bodyChannel

    @Deprecated(message = "TestApplicationEngine no longer supports IncomingContent", level = DeprecationLevel.ERROR)
    override fun receiveContent(): @Suppress("DEPRECATION") IncomingContent = error("TestApplicationEngine no longer supports IncomingContent")
}

fun TestApplicationRequest.setBody(value: String) {
    setBody(value.toByteArray())
}

fun TestApplicationRequest.setBody(value: ByteArray) {
    bodyChannel = ByteReadChannel(value)
}

fun TestApplicationRequest.setBody(boundary: String, values: List<PartData>): Unit = setBody(buildString {
    if (values.isEmpty()) return

    append("\r\n\r\n")
    values.forEach {
        append("--$boundary\r\n")
        it.headers.flattenForEach { key, value -> append("$key: $value\r\n") }
        append("\r\n")
        when (it) {
            is PartData.FileItem -> {
                val charset = headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }?.charset()
                        ?: Charsets.ISO_8859_1

                append(it.streamProvider().reader(charset).readText())
            }
            is PartData.FormItem -> append(it.value)
        }
        append("\r\n")
    }

    append("--$boundary--\r\n\r\n")
})
