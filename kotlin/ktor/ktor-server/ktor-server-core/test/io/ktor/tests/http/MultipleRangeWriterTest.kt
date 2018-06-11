package io.ktor.tests.http

import io.ktor.features.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.jvm.javaio.*
import org.junit.Test
import kotlin.test.*


class ByteRangesChannelTest {
    @Test
    fun testAscendingNoLength() {
        val source = asyncOf("0123456789abcdef")
        val result = writeMultipleRanges(source, listOf(1L .. 3L, 5L..6L), null, "boundary-1", "text/plain")

        assertEquals("""
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 1-3/*

        123
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 5-6/*

        56
        --boundary-1--

        """.trimIndent(), result.readText().replaceEndlines())
    }

    @Test
    fun testAscendingWithLength() {
        val source = asyncOf("0123456789abcdef")
        val ranges = writeMultipleRanges(source, listOf(1L .. 3L, 5L..6L), 99L, "boundary-1", "text/plain")

        assertEquals("""
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 1-3/99

        123
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 5-6/99

        56
        --boundary-1--

        """.trimIndent(), ranges.readText().replaceEndlines())
    }

    @Test
    fun testNonAscendingNoLength() {
        val source = asyncOf("0123456789abcdef")
        val ranges = writeMultipleRanges(source, listOf(1L .. 3L, 5L .. 6L, 0L .. 1L), null, "boundary-1", "text/plain")

        assertEquals("""
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 1-3/*

        123
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 5-6/*

        56
        --boundary-1
        Content-Type: text/plain
        Content-Range: bytes 0-1/*

        01
        --boundary-1--

        """.trimIndent(), ranges.readText().replaceEndlines())
    }

    private fun String.replaceEndlines() = replace("\r\n", "\n")
    private fun ByteReadChannel.readText(): String = toInputStream().reader(Charsets.ISO_8859_1).readText()

    private fun asyncOf(text: String): (LongRange) -> ByteReadChannel = { range ->
        ByteReadChannel(text.substring(range.start.toInt(), range.endInclusive.toInt() + 1).toByteArray(Charsets.ISO_8859_1))
    }
}