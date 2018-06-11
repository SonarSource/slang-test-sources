package io.ktor.sessions

import io.ktor.util.*
import java.security.*

private val delimiter = '/'

/**
 * Session transformer that appends an [algorithm] hash of the input.
 * Where the input is either a session contents or a previous transformation.
 * It prepends a [salt] when computing the hash.
 */
class SessionTransportTransformerDigest(val salt: String = "ktor", val algorithm: String = "SHA-256") : SessionTransportTransformer {

    override fun transformRead(transportValue: String): String? {
        val providedSignature = transportValue.substringAfterLast(delimiter, "")
        val value = transportValue.substringBeforeLast(delimiter)

        val providedBytes = try {
            hex(providedSignature)
        } catch (e: NumberFormatException) {
            return null
        }
        if (MessageDigest.isEqual(providedBytes, digest(value)))
            return value
        return null
    }

    override fun transformWrite(transportValue: String): String = transportValue + delimiter + hex(digest(transportValue))

    private fun digest(value: String): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        md.update(salt.toByteArray())
        md.update(value.toByteArray())
        return md.digest()
    }
}
