package io.ktor.server.testing

import io.ktor.application.*
import io.ktor.cio.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.content.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.jvm.javaio.*
import kotlinx.io.streams.*
import org.junit.Test
import org.junit.runners.model.*
import org.slf4j.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.security.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.zip.*
import kotlin.concurrent.*
import kotlin.coroutines.experimental.*
import kotlin.test.*

abstract class EngineTestSuite<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(hostFactory: ApplicationEngineFactory<TEngine, TConfiguration>) : EngineTestBase<TEngine, TConfiguration>(hostFactory) {
    @Test
    fun testTextContent() {
        createAndStartServer {
            handle {
                call.respondText("test")
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)

            val fields = HeadersBuilder()
            fields.appendAll(headers)

            fields.remove(HttpHeaders.Date) // Do not check for Date field since it's unstable

            // Check content type manually because spacing and case can be different per engine
            val contentType = fields.getAll(HttpHeaders.ContentType)?.single()
            fields.remove(HttpHeaders.ContentType)
            assertNotNull(contentType) // Content-Type should be present
            val parsedContentType = ContentType.parse(contentType!!) // It should parse
            assertEquals(ContentType.Text.Plain.withCharset(Charsets.UTF_8), parsedContentType)

            if (version == HttpProtocolVersion.HTTP_2_0) {
                assertEquals(mapOf(
                        "content-length" to listOf("4")), fields.build().toMap())
            } else {
                assertEquals(mapOf(
                        "Connection" to listOf("keep-alive"),
                        "Content-Length" to listOf("4")), fields.build().toMap())
            }

            assertEquals("test", readText())
        }
    }

    @Test
    @Http2Only
    fun testServerPush() {
        createAndStartServer {
            get("/child") {
                call.respondText("child")
            }

            get("/") {
                call.push("/child")
                call.respondText("test")
            }
        }
    }

    @Test
    fun testStream() {
        createAndStartServer {
            handle {
                call.respondWrite {
                    write("ABC")
                    flush()
                    write("123")
                    flush()
                }
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals("ABC123", readText())
        }
    }

    @Test
    fun testLoggerOnError() {
        val message = "expected, ${nextNonce()}"
        val collected = LinkedBlockingQueue<Throwable>()

        val log = object : Logger by LoggerFactory.getLogger("ktor.test") {
            override fun error(message: String, exception: Throwable?) {
                if (exception != null) {
                    collected.add(exception)
                }
            }
        }

        createAndStartServer(log) {
            get("/") {
                throw ExpectedException(message)
            }
            get("/respondWrite") {
                call.respondWrite {
                    throw ExpectedException(message)
                }
            }
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.InternalServerError.value, status.value)

            while (true) {
                val exception = collected.poll(timeout.seconds, TimeUnit.SECONDS)
                if (exception is ExpectedException) {
                    assertEquals(message, exception.message)
                    break
                }
            }
        }

        withUrl("/respondWrite") {
            assertEquals(HttpStatusCode.OK.value, status.value)
            while (true) {
                val exception = collected.poll(timeout.seconds, TimeUnit.SECONDS)
                if (exception is ExpectedException) {
                    assertEquals(message, exception.message)
                    break
                }
            }
        }
    }

    @Test
    fun testRequestContentFormData() {
        createAndStartServer {
            handle {
                val parameters = call.receiveOrNull<Parameters>()
                if (parameters != null)
                    call.respond(parameters.formUrlEncode())
                else
                    call.respond(HttpStatusCode.UnsupportedMediaType)
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            body = parametersOf("a", "1").formUrlEncode()
        }) {
            assertEquals(200, status.value)
            assertEquals("a=1", readText())
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.UnsupportedMediaType.value, status.value)
        }
    }

    @Test
    fun testStreamNoFlush() {
        createAndStartServer {
            handle {
                call.respondWrite {
                    write("ABC")
                    write("123")
                }
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals("ABC123", readText())
        }
    }

    @Test
    fun testSendTextWithContentType() {
        createAndStartServer {
            handle {
                call.respondText("Hello")
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals("Hello", readText())
            assertTrue(ContentType.parse(headers[HttpHeaders.ContentType]!!).match(ContentType.Text.Plain))
        }
    }

    @Test
    fun testRedirect() {
        createAndStartServer {
            handle {
                call.respondRedirect("http://localhost:${call.request.port()}/page", true)
            }
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.MovedPermanently.value, status.value)
        }
    }

    @Test
    fun testRedirectFromInterceptor() {
        createAndStartServer {
            application.intercept(ApplicationCallPipeline.Infrastructure) {
                call.respondRedirect("/2", true)
            }
        }

        withUrl("/1/") {
            assertEquals(HttpStatusCode.MovedPermanently.value, status.value)

            assertEquals("/2", headers[HttpHeaders.Location])
        }
    }

    @Test
    fun testHeader() {
        createAndStartServer {
            handle {
                call.response.headers.append(HttpHeaders.ETag, "test-etag")
                call.respondText("Hello")
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals("test-etag", headers[HttpHeaders.ETag])
            assertNull(headers[HttpHeaders.TransferEncoding])
            assertEquals("5", headers[HttpHeaders.ContentLength])
        }
    }

    @Test
    fun testHeadRequest() {
        createAndStartServer {
            install(AutoHeadResponse)
            handle {
                call.respondText("Hello")
            }
        }

        withUrl("/", { method = HttpMethod.Head }) {
            assertEquals(200, status.value)
            assertNull(headers[HttpHeaders.TransferEncoding])
            assertEquals("5", headers[HttpHeaders.ContentLength])
        }
    }

    @Test
    fun testCookie() {
        createAndStartServer {
            handle {
                call.response.cookies.append("k1", "v1")
                call.respondText("Hello")
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals("k1=v1; \$x-enc=URI_ENCODING", headers[HttpHeaders.SetCookie])
        }
    }

    @Test
    fun testStaticServe() {
        createAndStartServer {
            static("/files/") {
                resources("io/ktor/server/testing")
            }
        }

        withUrl("/files/${EngineTestSuite::class.simpleName}.class") {
            assertEquals(200, status.value)
            val bytes = readBytes(8192)
            assertNotEquals(0, bytes.size)

            // class file signature
            assertEquals(0xca, bytes[0].toInt() and 0xff)
            assertEquals(0xfe, bytes[1].toInt() and 0xff)
            assertEquals(0xba, bytes[2].toInt() and 0xff)
            assertEquals(0xbe, bytes[3].toInt() and 0xff)

            discardRemaining()
        }
        withUrl("/files/${EngineTestSuite::class.simpleName}.class2") {
            assertEquals(HttpStatusCode.NotFound.value, status.value)
            discardRemaining()
        }
        withUrl("/wefwefwefw") {
            assertEquals(HttpStatusCode.NotFound.value, status.value)
            discardRemaining()
        }
    }

    @Test
    fun testStaticServeFromDir() {
        val targetClasses = listOf(File(classesDir), File(coreClassesDir)).first { it.exists() }
        val file = targetClasses.walkBottomUp().filter { it.extension == "class" }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            static("/files") {
                files(targetClasses.path)
            }
        }

        withUrl("/files/${file.toRelativeString(targetClasses).urlPath()}") {
            assertEquals(200, status.value)

            val bytes = readBytes(100)
            assertNotEquals(0, bytes.size)

            // class file signature
            assertEquals(0xca, bytes[0].toInt() and 0xff)
            assertEquals(0xfe, bytes[1].toInt() and 0xff)
            assertEquals(0xba, bytes[2].toInt() and 0xff)
            assertEquals(0xbe, bytes[3].toInt() and 0xff)
        }

        withUrl("/files/${file.toRelativeString(targetClasses).urlPath()}2") {
            assertEquals(404, status.value)
        }
        withUrl("/wefwefwefw") {
            assertEquals(404, status.value)
        }
    }

    @Test
    fun testLocalFileContent() {
        val file = listOf(File("src"), File("ktor-server/ktor-server-core/src")).first { it.exists() }.walkBottomUp().filter { it.extension == "kt" }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            handle {
                call.respond(LocalFileContent(file))
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            assertEquals(file.readText(), readText(Charsets.UTF_8))
        }
    }

    @Test
    fun testLocalFileContentWithCompression() {
        val file = listOf(File("src"), File("ktor-server/ktor-server-core/src")).first { it.exists() }.walkBottomUp().filter { it.extension == "kt" }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            application.install(Compression)
            handle {
                call.respond(LocalFileContent(file))
            }
        }

        withUrl("/", {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }) {
            assertEquals(200, status.value)
            assertEquals(file.readText(), GZIPInputStream(content.toInputStream()).reader().use { it.readText() })
            assertEquals("gzip", headers[HttpHeaders.ContentEncoding])
        }
    }

    @Test
    fun testLocalFileContentRange() {
        val file = listOf(File("src"), File("ktor-server/ktor-server-core/src")).first { it.exists() }.walkBottomUp().filter { it.extension == "kt" && it.reader().use { it.read().toChar() == 'p' } }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            application.install(PartialContent)
            handle {
                call.respond(LocalFileContent(file))
            }
        }

        withUrl("/", {
            header(HttpHeaders.Range, RangesSpecifier(RangeUnits.Bytes, listOf(ContentRange.Bounded(0, 0))).toString())
        }) {
            assertEquals(HttpStatusCode.PartialContent.value, status.value)
            assertEquals("p", readText())
        }
        withUrl("/", {
            header(HttpHeaders.Range, RangesSpecifier(RangeUnits.Bytes, listOf(ContentRange.Bounded(1, 2))).toString())
        }) {
            assertEquals(HttpStatusCode.PartialContent.value, status.value)
            assertEquals("ac", readText())
        }
    }

    @Test
    fun testLocalFileContentRangeWithCompression() {
        val file = listOf(File("src"), File("ktor-server/ktor-server-core/src")).first { it.exists() }.walkBottomUp().filter { it.extension == "kt" && it.reader().use { it.read().toChar() == 'p' } }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            application.install(Compression)
            application.install(PartialContent)

            handle {
                call.respond(LocalFileContent(file))
            }
        }

        withUrl("/", {
            header(HttpHeaders.AcceptEncoding, "gzip")
            header(HttpHeaders.Range, RangesSpecifier(RangeUnits.Bytes, listOf(ContentRange.Bounded(0, 0))).toString())
        }) {
            assertEquals(HttpStatusCode.PartialContent.value, status.value)
            assertEquals("p", readText()) // it should be no compression if range requested
        }
    }

    @Test
    fun testJarFileContent() {
        createAndStartServer {
            handle {
                call.respond(call.resolveResource("/ArrayList.class", "java.util")!!)
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            readBytes().let { bytes ->
                assertNotEquals(0, bytes.size)

                // class file signature
                assertEquals(0xca, bytes[0].toInt() and 0xff)
                assertEquals(0xfe, bytes[1].toInt() and 0xff)
                assertEquals(0xba, bytes[2].toInt() and 0xff)
                assertEquals(0xbe, bytes[3].toInt() and 0xff)
            }
        }
    }

    @Test
    fun testURIContent() {
        createAndStartServer {
            handle {
                call.respond(URIFileContent(this::class.java.classLoader.getResources("java/util/ArrayList.class").toList().first()))
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            readBytes().let { bytes ->
                assertNotEquals(0, bytes.size)

                // class file signature
                assertEquals(0xca, bytes[0].toInt() and 0xff)
                assertEquals(0xfe, bytes[1].toInt() and 0xff)
                assertEquals(0xba, bytes[2].toInt() and 0xff)
                assertEquals(0xbe, bytes[3].toInt() and 0xff)
            }
        }
    }

    @Test
    fun testURIContentLocalFile() {
        val buildDir = "ktor-server/ktor-server-core/build/classes/kotlin/main"
        val file = listOf(File("build/classes/kotlin/main"), File(buildDir)).first { it.exists() }.walkBottomUp().filter { it.extension == "class" }.first()
        testLog.trace("test file is $file")

        createAndStartServer {
            handle {
                call.respond(URIFileContent(file.toURI()))
            }
        }

        withUrl("/") {
            assertEquals(200, status.value)
            readBytes().let { bytes ->
                assertNotEquals(0, bytes.size)

                // class file signature
                assertEquals(0xca, bytes[0].toInt() and 0xff)
                assertEquals(0xfe, bytes[1].toInt() and 0xff)
                assertEquals(0xba, bytes[2].toInt() and 0xff)
                assertEquals(0xbe, bytes[3].toInt() and 0xff)
            }
        }
    }

    @Test
    fun testPathComponentsDecoding() {
        createAndStartServer {
            get("/a%20b") {
                call.respondText("space")
            }
            get("/a+b") {
                call.respondText("plus")
            }
        }

        withUrl("/a%20b") {
            assertEquals(200, status.value)
            assertEquals("space", readText())
        }
        withUrl("/a+b") {
            assertEquals(200, status.value)
            assertEquals("plus", readText())
        }
    }

    @Test
    fun testFormUrlEncoded() {
        createAndStartServer {
            post("/") {
                call.respondText("${call.parameters["urlp"]},${call.receiveParameters()["formp"]}")
            }
        }

        withUrl("/?urlp=1", {
            method = HttpMethod.Post
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            body = ByteArrayContent("formp=2".toByteArray())
        }) {
            assertEquals(HttpStatusCode.OK.value, status.value)
            assertEquals("1,2", readText())
        }
    }

    @Test
    fun testRequestBodyAsyncEcho() {
        createAndStartServer {
            route("/echo") {
                handle {
                    val response = call.receiveChannel().toByteArray()
                    call.respond(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom() = ByteReadChannel(response)
                    })
                }
            }
        }

        withUrl("/echo", {
            method = HttpMethod.Post
            body = WriterContent({
                append("POST test\n")
                append("Another line")
                flush()
            }, ContentType.Text.Plain)
        }) {
            assertEquals(200, status.value)
            assertEquals("POST test\nAnother line", readText())
        }
    }

    @Test
    fun testEchoBlocking() {
        createAndStartServer {
            post("/") {
                val text = call.receiveStream().bufferedReader().readText()
                call.response.status(HttpStatusCode.OK)
                call.respond(text)
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            body = ByteArrayContent("POST content".toByteArray())
        }) {
            assertEquals(200, status.value)
            assertEquals("POST content", readText())
        }
    }

    @Test
    @NoHttp2
    fun testMultipartFileUpload() {
        createAndStartServer {
            post("/") {
                val response = StringBuilder()

                call.receiveMultipart().readAllParts().sortedBy { it.name }.forEach { part ->
                    when (part) {
                        is PartData.FormItem -> response.append("${part.name}=${part.value}\n")
                        is PartData.FileItem -> response.append("file:${part.name},${part.originalFileName},${part.streamProvider().bufferedReader().readText()}\n")
                    }

                    part.dispose()
                }

                call.respondText(response.toString())
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            val contentType = ContentType.MultiPart.FormData
                    .withParameter("boundary", "***bbb***")
                    .withCharset(Charsets.ISO_8859_1)

            body = WriterContent({
                append("--***bbb***\r\n")
                append("Content-Disposition: form-data; name=\"a story\"\r\n")
                append("\r\n")
                append("Hi user. The snake you gave me for free ate all the birds. Please take it back ASAP.\r\n")
                append("--***bbb***\r\n")
                append("Content-Disposition: form-data; name=\"attachment\"; filename=\"original.txt\"\r\n")
                append("Content-Type: text/plain\r\n")
                append("\r\n")
                append("File content goes here\r\n")
                append("--***bbb***--\r\n")
                flush()
            }, contentType)
        }) {
            assertEquals(200, status.value)
            assertEquals("a story=Hi user. The snake you gave me for free ate all the birds. Please take it back ASAP.\nfile:attachment,original.txt,File content goes here\n", readText())
        }
    }

    @Test
    @NoHttp2
    fun testMultipartFileUploadLarge() {
        val numberOfLines = 10000

        createAndStartServer {
            post("/") {
                val response = StringBuilder()

                call.receiveMultipart().forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> response.append("${part.name}=${part.value}\n")
                        is PartData.FileItem -> response.append("file:${part.name},${part.originalFileName},${part.streamProvider().bufferedReader().lineSequence().count()}\n")
                    }

                    part.dispose()
                }

                call.respondText(response.toString())
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            val contentType = ContentType.MultiPart.FormData
                    .withParameter("boundary", "***bbb***")
                    .withCharset(Charsets.ISO_8859_1)

            body = WriterContent({
                append("--***bbb***\r\n")
                append("Content-Disposition: form-data; name=\"a story\"\r\n")
                append("\r\n")
                append("Hi user. The snake you gave me for free ate all the birds. Please take it back ASAP.\r\n")
                append("--***bbb***\r\n")
                append("Content-Disposition: form-data; name=\"attachment\"; filename=\"original.txt\"\r\n")
                append("Content-Type: text/plain\r\n")
                append("\r\n")
                withContext(CommonPool) {
                    repeat(numberOfLines) {
                        append("File content goes here\r\n")
                    }
                }
                append("--***bbb***--\r\n")
                flush()
            }, contentType)
        }) {
            assertEquals(200, status.value)
            assertEquals("a story=Hi user. The snake you gave me for free ate all the birds. Please take it back ASAP.\nfile:attachment,original.txt,$numberOfLines\n", readText())
        }
    }

    @Test
    fun testRequestTwiceNoKeepAlive() {
        createAndStartServer {
            get("/") {
                call.respondText("Text")
            }
        }

        withUrl("/", {
            header(HttpHeaders.Connection, "close")
        }) {
            assertEquals("Text", readText())
        }

        withUrl("/", {
            header(HttpHeaders.Connection, "close")
        }) {
            assertEquals("Text", readText())
        }
    }

    @Test
    fun testRequestTwiceWithKeepAlive() {
        createAndStartServer {
            get("/") {
                call.respondText("Text")
            }
        }


        withUrl("/", {
            header(HttpHeaders.Connection, "keep-alive")
        }) {
            assertEquals(200, status.value)
            assertEquals("Text", readText())
        }

        withUrl("/", {
            header(HttpHeaders.Connection, "keep-alive")
        }) {
            assertEquals(200, status.value)
            assertEquals("Text", readText())
        }
    }

    @Test
    fun testRequestTwiceInOneBufferWithKeepAlive() {
        createAndStartServer {
            get("/") {
                val d = call.request.queryParameters["d"]!!.toLong()
                delay(d, TimeUnit.SECONDS)

                call.response.header("D", d.toString())
                call.respondText("Response for $d\n")
            }
        }

        val s = Socket()
        s.tcpNoDelay = true

        val impudent = buildString {
            append("GET /?d=2 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")

            append("GET /?d=1 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()

        s.connect(InetSocketAddress(port))
        s.use {
            s.getOutputStream().apply {
                write(impudent)
                flush()
            }

            val responses = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).lineSequence()
                    .filterNot { it.startsWith("Date") || it.startsWith("Server") || it.startsWith("Content-") || it.toIntOrNull() != null || it.isBlank() || it.startsWith("Connection") }
                    .map { it.trim() }
                    .joinToString(separator = "\n").replace("200 OK", "200")

            assertEquals("""
                HTTP/1.1 200
                D: 2
                Response for 2
                HTTP/1.1 200
                D: 1
                Response for 1
                """.trimIndent().replace("\r\n", "\n"), responses)
        }
    }

    @Test
    fun testRequestContentString() {
        createAndStartServer {
            post("/") {
                call.respond(call.receiveText())
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            body = "Hello"
        }) {
            assertEquals(200, status.value)
            assertEquals("Hello", readText())
        }
    }

    @Test
    fun testReceiveInputStream() {
        createAndStartServer {
            post("/") {
                @Suppress("DEPRECATION")
                call.respond(call.request.receiveContent().inputStream().reader().readText())
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            body = "Hello"
        }) {
            assertEquals(200, status.value)
            assertEquals("Hello", readText())
        }
    }

    @Test
    fun testRepeatRequest() {
        createAndStartServer {
            get("/") {
                call.respond("OK ${call.request.queryParameters["i"]}")
            }
        }

        for (i in 1..100) {
            withUrl("/?i=$i") {
                assertEquals(200, status.value)
                assertEquals("OK $i", readText())
            }
        }
    }

    @Test
    fun testRequestContentInputStream() {
        createAndStartServer {
            post("/") {
                call.respond(call.receiveStream().reader().readText())
            }
        }

        withUrl("/", {
            method = HttpMethod.Post
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            body = ByteArrayContent("Hello".toByteArray())
        }) {
            assertEquals(200, status.value)
            assertEquals("Hello", readText())
        }
    }

    @Test
    fun testStatusCodeDirect() {
        createAndStartServer {
            get("/") {
                call.response.status(HttpStatusCode.Found)
                call.respond("Hello")
            }
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.Found.value, status.value)
            assertEquals("Hello", readText())
        }
    }

    @Test
    fun testStatusCodeViaResponseObject() {
        var completed = false
        createAndStartServer {
            get("/") {
                call.respond(HttpStatusCode.Found)
            }
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.Found.value, status.value)
            completed = true
        }
        assertTrue(completed)
    }

    @Test
    fun test404() {
        createAndStartServer {
        }

        withUrl("/") {
            assertEquals(HttpStatusCode.NotFound.value, status.value)
        }

        withUrl("/aaaa") {
            assertEquals(HttpStatusCode.NotFound.value, status.value)
        }
    }

    @Test
    fun testProxyHeaders() {
        createAndStartServer {
            install(XForwardedHeadersSupport)
            get("/") {
                call.respond(call.url { })
            }
        }

        withUrl("/", {
            header(HttpHeaders.XForwardedHost, "my-host:90")
        }) { port ->
            val expectedProto = if (port == sslPort) "https" else "http"
            assertEquals("$expectedProto://my-host:90/", readText())
        }

        withUrl("/", {
            header(HttpHeaders.XForwardedHost, "my-host")
        }) { port ->
            val expectedProto = if (port == sslPort) "https" else "http"
            assertEquals("$expectedProto://my-host/", readText())
        }

        withUrl("/", {
            header(HttpHeaders.XForwardedHost, "my-host:90")
            header(HttpHeaders.XForwardedProto, "https")
        }) {
            assertEquals("https://my-host:90/", readText())
        }

        withUrl("/", {
            header(HttpHeaders.XForwardedHost, "my-host")
            header(HttpHeaders.XForwardedProto, "https")
        }) {
            assertEquals("https://my-host/", readText())
        }
    }

    @Test
    fun testRequestParts() {
        createAndStartServer {
            get("/path/1") {
                call.respond(call.request.path())
            }
            get("/document/1") {
                call.respond(call.request.document())
            }
            get("/queryString/1") {
                call.respond(call.request.queryString())
            }
            get("/uri/1") {
                call.respond(call.request.uri)
            }
        }

        withUrl("/path/1?p=v") {
            assertEquals("/path/1", readText())
        }
        withUrl("/path/1?") {
            assertEquals("/path/1", readText())
        }
        withUrl("/path/1") {
            assertEquals("/path/1", readText())
        }

        withUrl("/document/1?p=v") {
            assertEquals("1", readText())
        }
        withUrl("/document/1?") {
            assertEquals("1", readText())
        }
        withUrl("/document/1") {
            assertEquals("1", readText())
        }

        withUrl("/queryString/1?p=v") {
            assertEquals("p=v", readText())
        }
        withUrl("/queryString/1?") {
            assertEquals("", readText())
        }
        withUrl("/queryString/1") {
            assertEquals("", readText())
        }

        withUrl("/uri/1?p=v") {
            assertEquals("/uri/1?p=v", readText())
        }
        withUrl("/uri/1?") {
            assertEquals("/uri/1?", readText())
        }
        withUrl("/uri/1") {
            assertEquals("/uri/1", readText())
        }
    }

    @Test
    fun testRequestParameters() {
        createAndStartServer {
            get("/*") {
                call.respond(call.request.queryParameters.getAll(call.request.path().removePrefix("/")).toString())
            }
        }

        withUrl("/single?single=value") {
            assertEquals("[value]", readText())
        }
        withUrl("/multiple?multiple=value1&multiple=value2") {
            assertEquals("[value1, value2]", readText())
        }
        withUrl("/missing") {
            assertEquals("null", readText())
        }
    }

    @Test
    fun testBlockingConcurrency() {
        val completed = AtomicInteger(0)
        createAndStartServer {
            get("/{index}") {
                val index = call.parameters["index"]!!.toInt()
                call.respondWrite {
                    //print("[$index] ")
                    try {
                        append("OK:$index\n")
                    } finally {
                        completed.incrementAndGet()
                    }
                }
            }
        }

        val count = 100
        val latch = CountDownLatch(count)
        val errors = CopyOnWriteArrayList<Throwable>()

        val random = Random()
        for (i in 1..latch.count) {
            thread {
                try {
                    withUrl("/$i") {
                        //setRequestProperty("Connection", "close")
                        content.toInputStream().reader().use { reader ->
                            val firstByte = reader.read()
                            if (firstByte == -1) {
                                //println("Premature end of response stream at iteration $i")
                                kotlin.test.fail("Premature end of response stream at iteration $i")
                            } else {
                                assertEquals('O', firstByte.toChar())
                                Thread.sleep(random.nextInt(1000).toLong())
                                assertEquals("K:$i\n", reader.readText())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()

        if (errors.isNotEmpty()) {
            throw MultipleFailureException(errors)
        }

        var multiplier = 1
        if (enableHttp2) multiplier++
        if (enableSsl) multiplier++

        assertEquals(count * multiplier, completed.get())
    }

    @Test
    fun testBigFile() {
        val file = File("build/large-file.dat")
        val rnd = Random()

        if (!file.exists()) {
            file.bufferedWriter().use { out ->
                for (line in 1..9000000) {
                    for (col in 1..(30 + rnd.nextInt(40))) {
                        out.append('a' + rnd.nextInt(25))
                    }
                    out.append('\n')
                }
            }
        }

        val originalSha1WithSize = file.inputStream().use { it.sha1WithSize() }

        createAndStartServer {
            get("/file") {
                call.respond(LocalFileContent(file))
            }
        }

        withUrl("/file") {
            assertEquals(originalSha1WithSize, content.toInputStream().sha1WithSize())
        }
    }

    @Test
    fun testBigFileHttpUrlConnection() {
        val file = File("build/large-file.dat")
        val rnd = Random()

        if (!file.exists()) {
            file.bufferedWriter().use { out ->
                for (line in 1..9000000) {
                    for (col in 1..(30 + rnd.nextInt(40))) {
                        out.append('a' + rnd.nextInt(25))
                    }
                    out.append('\n')
                }
            }
        }

        val originalSha1WithSize = file.inputStream().use { it.sha1WithSize() }

        createAndStartServer {
            get("/file") {
                call.respond(LocalFileContent(file))
            }
        }

        val connection = URL("http://localhost:$port/file").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        try {
            assertEquals(originalSha1WithSize, connection.inputStream.sha1WithSize())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun testClosedConnection() {
        val completed = Job()

        createAndStartServer {
            get("/file") {
                try {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            val bb = ByteBuffer.allocate(100)
                            for (i in 1L..1000L) {
                                delay(100)
                                bb.clear()
                                bb.putLong(i)
                                bb.flip()
                                channel.writeFully(bb)
                                channel.flush()
                            }

                            channel.close()
                        }
                    })
                } finally {
                    completed.cancel()
                }
            }
        }

        socket {
            outputStream.writePacket(RequestResponseBuilder().apply {
                requestLine(HttpMethod.Get, "/file", "HTTP/1.1")
                headerLine("Host", "localhost:$port")
                headerLine("Connection", "keep-alive")
                emptyLine()
            }.build())

            outputStream.flush()

            inputStream.read(ByteArray(100))
            shutdownInput()
            shutdownOutput()
        }

        runBlocking {
            withTimeout(1000L, TimeUnit.SECONDS) {
                completed.join()
            }
        }
    }

    @Test
    fun testStatusPages404() {
        createAndStartServer {
            application.install(StatusPages) {
                status(HttpStatusCode.NotFound) {
                    call.respondWrite(ContentType.parse("text/html"), HttpStatusCode.NotFound) {
                        write("Error string")
                    }
                }
            }
        }

        withUrl("/non-existent") {
            assertEquals(HttpStatusCode.NotFound.value, status.value)
            assertEquals("Error string", readText())
        }
    }

    @Test
    open fun testBlockingDeadlock() {
        createAndStartServer {
            get("/") {
                call.respondWrite(ContentType.Text.Plain.withCharset(Charsets.ISO_8859_1)) {
                    TimeUnit.SECONDS.sleep(1)
                    this.write("Deadlock ?")
                }
            }
        }

        val e = Executors.newCachedThreadPool()
        try {
            val q = LinkedBlockingQueue<String>()

            val conns = (0..callGroupSize * 10).map {
                e.submit(Callable<String> {
                    try {
                        URL("http://localhost:$port/").openConnection().inputStream.bufferedReader().readLine().apply {
                            //println("$number says $this")
                        } ?: "<empty>"
                    } catch (t: Throwable) {
                        "error: ${t.message}"
                    }.apply {
                        q.add(this)
                    }
                })
            }

            TimeUnit.SECONDS.sleep(5)
            var attempts = 7

            fun dump() {
//            val (valid, invalid) = conns.filter { it.isDone }.partition { it.get() == "Deadlock ?" }
//
//            println("Completed: ${valid.size} valid, ${invalid.size} invalid of ${conns.size} total [attempts $attempts]")
            }

            while (true) {
                dump()

                if (conns.all { it.isDone }) {
                    break
                } else if (q.poll(5, TimeUnit.SECONDS) == null) {
                    if (attempts <= 0) {
                        break
                    }
                    attempts--
                } else {
                    attempts = 7
                }
            }

            dump()

            /* use for debugging */
//        if (conns.any { !it.isDone }) {
//             TimeUnit.SECONDS.sleep(500)
//        }

            assertTrue { conns.all { it.isDone } }
        } finally {
            e.shutdownNow()
        }
    }

    @Test
    open fun testUpgrade() {
        createAndStartServer {
            get("/up") {
                call.respond(object : OutgoingContent.ProtocolUpgrade() {
                    override val headers: Headers
                        get() = Headers.build {
                            append(HttpHeaders.Upgrade, "up")
                            append(HttpHeaders.Connection, "Upgrade")
                        }

                    override suspend fun upgrade(input: ByteReadChannel, output: ByteWriteChannel, engineContext: CoroutineContext, userContext: CoroutineContext): Job {
                        return launch(engineContext) {
                            val bb = ByteBuffer.allocate(8)
                            input.readFully(bb)
                            bb.flip()
                            output.writeFully(bb)
                            output.close()
                        }
                    }
                })
            }
        }

        socket {
            outputStream.apply {
                val p = RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/up", "HTTP/1.1")
                    headerLine(HttpHeaders.Host, "localhost:$port")
                    headerLine(HttpHeaders.Upgrade, "up")
                    headerLine(HttpHeaders.Connection, "upgrade")
                    emptyLine()
                }.build()
                writePacket(p)
                flush()
            }

            val ch = ByteChannel(true)

            runBlocking {
                launch(coroutineContext) {
                    val s = inputStream
                    val bytes = ByteArray(512)
                    try {
                        while (true) {
                            if (s.available() > 0) {
                                val rc = s.read(bytes)
                                ch.writeFully(bytes, 0, rc)
                            } else {
                                yield()
                                val rc = s.read(bytes)
                                if (rc == -1) break
                                ch.writeFully(bytes, 0, rc)
                            }

                            yield()
                        }
                    } catch (t: Throwable) {
                        ch.close(t)
                    } finally {
                        ch.close()
                    }
                }

                val response = parseResponse(ch)!!

                assertEquals(HttpStatusCode.SwitchingProtocols.value, response.status)
                assertEquals("Upgrade", response.headers[HttpHeaders.Connection]?.toString())
                assertEquals("up", response.headers[HttpHeaders.Upgrade]?.toString())

                (0 until response.headers.size)
                        .map { response.headers.nameAt(it).toString() }
                        .groupBy { it }.forEach { (name, values) ->
                    assertEquals(1, values.size, "Duplicate header $name")
                }

                outputStream.apply {
                    writePacket {
                        writeLong(0x1122334455667788L)
                    }
                    flush()
                }

                assertEquals(0x1122334455667788L, ch.readLong())

                assertEquals(-1, ch.readAvailable(ByteArray(1)))
            }
        }
    }

    @Test
    @NoHttp2
    open fun testChunked() {
        val data = ByteArray(16 * 1024, { it.toByte() })
        val size = data.size.toLong()

        createAndStartServer {
            get("/chunked") {
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(data)
                        channel.close()
                    }
                })
            }
            get("/pseudo-chunked") {
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? get() = size
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(data)
                        channel.close()
                    }
                })
            }
            get("/array") {
                call.respond(object : OutgoingContent.ByteArrayContent() {
                    override val contentLength: Long? get() = size
                    override fun bytes(): ByteArray = data
                })
            }
            get("/array-chunked") {
                call.respond(object : OutgoingContent.ByteArrayContent() {
                    override fun bytes(): ByteArray = data
                })
            }
            get("/read-channel") {
                call.respond(object : OutgoingContent.ReadChannelContent() {
                    override fun readFrom(): ByteReadChannel = ByteReadChannel(data)
                })
            }
            get("/fixed-read-channel") {
                call.respond(object : OutgoingContent.ReadChannelContent() {
                    override val contentLength: Long? get() = size
                    override fun readFrom(): ByteReadChannel = ByteReadChannel(data)
                })
            }
        }

        withUrl("/array") {
            assertEquals(size, headers[HttpHeaders.ContentLength]?.toLong())
            assertNotEquals("chunked", headers[HttpHeaders.TransferEncoding])
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
        }

        withUrl("/array-chunked") {
            assertEquals("chunked", headers[HttpHeaders.TransferEncoding])
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
            assertNull(headers[HttpHeaders.ContentLength])
        }

        withUrl("/chunked") {
            assertEquals("chunked", headers[HttpHeaders.TransferEncoding])
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
            assertNull(headers[HttpHeaders.ContentLength])
        }

        withUrl("/fixed-read-channel") {
            assertNotEquals("chunked", headers[HttpHeaders.TransferEncoding])
            assertEquals(size, headers[HttpHeaders.ContentLength]?.toLong())
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
        }

        withUrl("/pseudo-chunked") {
            assertNotEquals("chunked", headers[HttpHeaders.TransferEncoding])
            assertEquals(size, headers[HttpHeaders.ContentLength]?.toLong())
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
        }

        withUrl("/read-channel") {
            assertNull(headers[HttpHeaders.ContentLength])
            assertEquals("chunked", headers[HttpHeaders.TransferEncoding])
            org.junit.Assert.assertArrayEquals(data, call.response.readBytes())
        }
    }

    @Test
    @NoHttp2
    @Ignore
    open fun testChunkedWrongLength() {
        val data = ByteArray(16 * 1024, { it.toByte() })
        val doubleSize = (data.size * 2).toString()
        val halfSize = (data.size / 2).toString()

        createAndStartServer {
            get("/read-less") {
                assertFailsSuspend {
                    call.respond(object : OutgoingContent.ReadChannelContent() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.ContentLength, doubleSize)
                            }

                        override fun readFrom(): ByteReadChannel = ByteReadChannel(data)
                    })
                }
            }
            get("/read-more") {
                assertFailsSuspend {
                    call.respond(object : OutgoingContent.ReadChannelContent() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.ContentLength, halfSize)
                            }

                        override fun readFrom(): ByteReadChannel = ByteReadChannel(data)
                    })
                }
            }
            get("/write-less") {
                assertFailsSuspend {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.ContentLength, doubleSize)
                            }

                        suspend override fun writeTo(channel: ByteWriteChannel) {
                            channel.writeFully(data)
                            channel.close()
                        }
                    })
                }
            }
            get("/write-more") {
                assertFailsSuspend {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.ContentLength, halfSize)
                            }

                        suspend override fun writeTo(channel: ByteWriteChannel) {
                            channel.writeFully(data)
                            channel.close()
                        }
                    })
                }
            }
        }

        assertFails {
            withUrl("/read-more") {
                call.receive<String>()
            }
        }

        assertFails {
            withUrl("/write-more") {
                call.receive<String>()
            }
        }

        assertFails {
            withUrl("/read-less") {
                call.receive<String>()
            }
        }

        assertFails {
            withUrl("/write-less") {
                call.receive<String>()
            }
        }
    }

    private fun String.urlPath() = replace("\\", "/")
    private class ExpectedException(message: String) : RuntimeException(message)

    private fun InputStream.sha1WithSize(): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA1")
        val bytes = ByteArray(8192)
        var count = 0L

        do {
            val rc = read(bytes)
            if (rc == -1) {
                break
            }
            count += rc
            md.update(bytes, 0, rc)
        } while (true)

        return hex(md.digest()) to count
    }

    companion object {
        val classesDir = "build/classes/kotlin/main"
        val coreClassesDir = "ktor-server/ktor-server-core/${classesDir}"
    }
}
