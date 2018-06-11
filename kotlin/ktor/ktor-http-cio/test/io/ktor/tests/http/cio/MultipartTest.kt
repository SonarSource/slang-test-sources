package io.ktor.tests.http.cio

import io.ktor.http.cio.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import org.junit.Test
import kotlin.test.*

class MultipartTest {
    @Test
    fun smokeTest() = runBlocking {
        val body = """
            POST /send-message.html HTTP/1.1
            Host: webmail.example.com
            Referer: http://webmail.example.com/send-message.html
            User-Agent: BrowserForDummies/4.67b
            Content-Type: multipart/form-data; boundary=Asrf456BGe4h
            Connection: close
            Keep-Alive: 300

            preamble
            --Asrf456BGe4h
            Content-Disposition: form-data; name="DestAddress"

            recipient@example.com
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageTitle"

            Good news
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageText"

            See attachments...
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile1"; filename="horror-photo-1.jpg"
            Content-Type: image/jpeg

            JFIF first
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile2"; filename="horror-photo-2.jpg"
            Content-Type: image/jpeg

            JFIF second
            --Asrf456BGe4h--
            epilogue
            """.trimIndent().lines().joinToString("\r\n")

        val ch = ByteReadChannel(body.toByteArray())
        val request = parseRequest(ch)!!
        val mp = parseMultipart(CommonPool, ch, request.headers)

        val allEvents = ArrayList<MultipartEvent>()
        mp.consumeEach { allEvents.add(it) }

        assertEquals(6, allEvents.size)

        val preamble = allEvents[0] as MultipartEvent.Preamble
        assertEquals("preamble\r\n", preamble.body.readText())

        val recipient = allEvents[1] as MultipartEvent.MultipartPart
        assertEquals("recipient@example.com", recipient.body.readRemaining().readText())

        val title = allEvents[2] as MultipartEvent.MultipartPart
        assertEquals("Good news", title.body.readRemaining().readText())

        val text = allEvents[3] as MultipartEvent.MultipartPart
        assertEquals("See attachments...", text.body.readRemaining().readText())

        val jpeg1 = allEvents[4] as MultipartEvent.MultipartPart
        assertEquals("JFIF first", jpeg1.body.readRemaining().readText())

        val jpeg2 = allEvents[5] as MultipartEvent.MultipartPart
        assertEquals("JFIF second", jpeg2.body.readRemaining().readText())
    }

    @Test
    fun smokeTestFirefox() = runBlocking {
        // captured from Firefox
        val body = """
            POST /mp HTTP/1.1
            Host: localhost:9096
            User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:56.0) Gecko/20100101 Firefox/56.0
            Accept-Language: ru-RU,ru;q=0.5
            Accept-Encoding: gzip, deflate
            Referer: http://localhost:9096/mp
            Content-Type: multipart/form-data; boundary=---------------------------13173666125065307431959751823
            Content-Length: 712
            Connection: keep-alive
            Upgrade-Insecure-Requests: 1
            Pragma: no-cache
            Cache-Control: no-cache

            -----------------------------13173666125065307431959751823
            Content-Disposition: form-data; name="title"

            Hello
            -----------------------------13173666125065307431959751823
            Content-Disposition: form-data; name="body"; filename="bug"
            Content-Type: application/octet-stream

            Let's assume we have cwd `/absolute/path/to/dir`, there is node_modules and webpack.config.js on the right place.

            ```
                "resolve": {
                    "modules": [
                        "node_modules" // this works well
                        //"/absolute/path/to/dir/node_modules"   // this doens't
                    ]
                }
            ```

            plain webpack works well with both but webpack-dev-server fails with error

            -----------------------------13173666125065307431959751823--

            """.trimIndent().lines().joinToString("\r\n")

        val ch = ByteReadChannel(body.toByteArray())
        val request = parseRequest(ch)!!
        val mp = parseMultipart(CommonPool, ch, request.headers)

        val allEvents = ArrayList<MultipartEvent>()
        mp.consumeEach { allEvents.add(it) }

        val parts = allEvents.filterIsInstance<MultipartEvent.MultipartPart>()
        val title = parts.getOrNull(0) ?: fail("No title part found")
        val file = parts.getOrNull(1) ?: fail("No file part found")

        assertEquals("Hello", title.body.readRemaining().readText())
        val fileContent = file.body.readRemaining().readText()
//        println(fileContent)
        assertEquals(380, fileContent.length)
    }

    @Test
    fun testMultipartFormDataChunkedEncoded() = runBlocking {
        val body = """
            POST /send-message.html HTTP/1.1
            Content-Type: multipart/form-data; boundary=Asrf456BGe4h
            Connection: keep-alive
            Transfer-Encoding: chunked

            248
            preamble
            --Asrf456BGe4h
            Content-Disposition: form-data; name="DestAddress"

            recipient@example.com
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageTitle"

            Good news
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageText"

            See attachments...
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile1"; filename="horror-photo-1.jpg"
            Content-Type: image/jpeg

            JFIF first
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile2"; filename="horror-photo-2.jpg"
            Content-Type: image/jpeg

            JFIF second
            --Asrf456BGe4h--
            epilogue
            0
            """.trimIndent().lines().joinToString("\r\n", postfix = "\r\n\r\n")

        val ch = ByteReadChannel(body.toByteArray())
        val request = parseRequest(ch)!!
        val decoded = decodeChunked(ch, CommonPool)
        val mp = parseMultipart(CommonPool, decoded.channel, request.headers)

        val allEvents = ArrayList<MultipartEvent>()
        mp.consumeEach { allEvents.add(it) }

        assertEquals(6, allEvents.size)

        val preamble = allEvents[0] as MultipartEvent.Preamble
        assertEquals("preamble\r\n", preamble.body.readText())

        val recipient = allEvents[1] as MultipartEvent.MultipartPart
        assertEquals("recipient@example.com", recipient.body.readRemaining().readText())

        val title = allEvents[2] as MultipartEvent.MultipartPart
        assertEquals("Good news", title.body.readRemaining().readText())

        val text = allEvents[3] as MultipartEvent.MultipartPart
        assertEquals("See attachments...", text.body.readRemaining().readText())

        val jpeg1 = allEvents[4] as MultipartEvent.MultipartPart
        assertEquals("JFIF first", jpeg1.body.readRemaining().readText())

        val jpeg2 = allEvents[5] as MultipartEvent.MultipartPart
        assertEquals("JFIF second", jpeg2.body.readRemaining().readText())
    }
}