package io.ktor.tests.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import kotlinx.coroutines.experimental.*
import org.junit.Test
import java.net.*
import java.time.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.test.*

class HttpClientTest {
    @Test
    fun testDefaultHttpConnection() = runBlocking {
        val portSync = ArrayBlockingQueue<Int>(1)
        val headersSync = ArrayBlockingQueue<Map<String, String>>(1)
        val receivedContentSync = ArrayBlockingQueue<String>(1)

        val th = thread {
            ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
                portSync.add(server.localPort)

                server.accept()!!.use { client ->
                    val reader = client.inputStream.bufferedReader()

                    val headers = reader.lineSequence().takeWhile { it.isNotBlank() }.associateBy(
                            { it.substringBefore(":", "") },
                            { it.substringAfter(":").trimStart() }
                    )
                    headersSync.add(headers)

                    val bodyLength = headers[HttpHeaders.ContentLength]?.toInt()
                            ?: error("Header Content-Length is missing or invalid")
                    val requestContentBuffer = CharArray(bodyLength)

                    var read = 0
                    while (read < requestContentBuffer.size) {
                        val rc = reader.read(requestContentBuffer, read, requestContentBuffer.size - read)
                        require(rc != -1) { "premature end of stream" }

                        read += rc
                    }

                    val requestContent = String(requestContentBuffer)
                    receivedContentSync.add(requestContent)

                    client.outputStream.writer().apply {
                        write("""
                    HTTP/1.1 200 OK
                    Server: test
                    Date: ${LocalDateTime.now().toHttpDateString()}
                    Connection: close
                    """.trimIndent().lines().joinToString("\r\n", postfix = "\r\n\r\nok"))
                        flush()
                    }
                }
            }
        }

        val port = portSync.take()
        val client = HttpClient(Apache)
        val response = client.call("http://127.0.0.1:$port/") {
            method = HttpMethod.Post
            url.encodedPath = "/url"
            header("header", "value")
            body = "request-body"
        }.response

        try {
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("test", response.headers[HttpHeaders.Server])
            assertEquals("ok", response.readText())

            val receivedHeaders = headersSync.take()
            assertEquals("value", receivedHeaders["header"])
            assertEquals("POST /url HTTP/1.1", receivedHeaders[""])
            assertEquals("127.0.0.1:$port", receivedHeaders[HttpHeaders.Host])

            assertEquals("request-body", receivedContentSync.take())
        } finally {
            response.close()
            client.close()
            th.join()
        }
    }
}
