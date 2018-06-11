package io.ktor.http.cio.internals

import io.ktor.http.*
import kotlinx.io.core.*
import java.nio.*

internal fun CharSequence.hashCodeLowerCase(start: Int = 0, end: Int = length): Int {
    var hashCode = 0
    for (pos in start until end) {
        val v = get(pos).toInt().toLowerCase()
        hashCode = 31 * hashCode + v
    }

    return hashCode
}

internal fun CharSequence.equalsLowerCase(start: Int = 0, end: Int = length, other: CharSequence): Boolean {
    if  (end - start != other.length) return false

    for (pos in start until end) {
        if (get(pos).toInt().toLowerCase() != other.get(pos - start).toInt().toLowerCase()) return false
    }

    return true
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Int.toLowerCase() = if (this in 'A'.toInt() .. 'Z'.toInt()) 'a'.toInt() + (this - 'A'.toInt()) else this

internal val DefaultHttpMethods =
    AsciiCharTree.build(HttpMethod.DefaultMethods, { it.value.length }, { m, idx -> m.value[idx] })


private val HexTable = (0..0xff).map { v ->
    when {
        v in 0x30..0x39 -> v - 0x30L
        v >= 'a'.toLong() && v <= 'f'.toLong() -> v - 'a'.toLong() + 10
        v >= 'A'.toLong() && v <= 'F'.toLong() -> v - 'A'.toLong() + 10
        else -> -1L
    }
}.toTypedArray()

private val HexLetterTable = (0..0xf).map {
    if (it < 0xa) (0x30 + it).toByte() else ('a' + it - 0x0a).toInt().toByte()
}.toTypedArray()

internal fun CharSequence.parseHexLong(): Long {
    var result = 0L
    val table = HexTable
    for (i in 0 until length) {
        val v = this[i].toInt() and 0xffff
        val digit = if (v < 0xff) table[v] else -1L
        if (digit == -1L) hexNumberFormatException(this, i)
        result = (result shl 4) or digit
    }

    return result
}

internal fun CharSequence.parseDecLong(): Long {
    val length = length
    if (length > 19) numberFormatException(this)
    if (length == 19) return parseDecLongWithCheck()

    var result = 0L
    for (i in 0 until length) {
        val digit = this[i].toLong() - 0x30L
        if (digit < 0 || digit > 9) numberFormatException(this, i)

        result = (result shl 3) + (result shl 1) + digit
    }

    return result
}

private fun CharSequence.parseDecLongWithCheck(): Long {
    var result = 0L
    for (i in 0 until length) {
        val digit = this[i].toLong() - 0x30L
        if (digit < 0 || digit > 9) numberFormatException(this, i)

        result = (result shl 3) + (result shl 1) + digit
        if (result < 0) numberFormatException(this)
    }

    return result
}

internal fun ByteBuffer.writeIntHex(value: Int): Int {
    require(value > 0) { "Does only work for positive numbers" } // zero is not included!
    var current = value
    var zeroes = 0
    val table = HexLetterTable

    repeat(8) { idx ->
        val v = current and 0x0f
        if (v == 0) zeroes++ else zeroes = 0
        current = current ushr 4

        put(7 - idx, table[v])
    }

    return zeroes
}

internal fun BufferView.writeIntHex(value: Int) {
    require(value > 0) { "Does only work for positive numbers" } // zero is not included!
    var current = value
    val table = HexLetterTable
    var digits = 0

    while (digits++ < 8) {
        val v = current ushr 28
        current = current shl 4

        if (v != 0) {
            writeByte(table[v])
            break
        }
    }

    while (digits++ < 8) {
        val v = current ushr 28
        current = current shl 4
        writeByte(table[v])
    }
}

private fun hexNumberFormatException(s: CharSequence, idx: Int): Nothing {
    throw NumberFormatException("Invalid HEX number: $s, wrong digit: ${s[idx]}")
}

private fun numberFormatException(cs: CharSequence, idx: Int) {
    throw NumberFormatException("Invalid number: $cs, wrong digit: ${cs[idx]} at position $idx")
}

private fun numberFormatException(cs: CharSequence) {
    throw NumberFormatException("Invalid number $cs: too large for Long type")
}

