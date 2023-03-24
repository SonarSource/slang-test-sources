/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.darwin

import io.ktor.client.call.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.posix.*

@OptIn(DelicateCoroutinesApi::class)
internal suspend fun OutgoingContent.toNSData(): NSData? = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().toNSData()
    is OutgoingContent.WriteChannelContent -> GlobalScope.writer(Dispatchers.Unconfined) {
        writeTo(channel)
    }.channel.readRemaining().readBytes().toNSData()
    is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readBytes().toNSData()
    is OutgoingContent.NoContent -> null
    else -> throw UnsupportedContentTypeException(this)
}

@OptIn(UnsafeNumber::class)
internal fun ByteArray.toNSData(): NSData = NSMutableData().apply {
    if (isEmpty()) return@apply
    this@toNSData.usePinned {
        appendBytes(it.addressOf(0), size.convert())
    }
}

@OptIn(UnsafeNumber::class)
internal fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) return result

    result.usePinned {
        memcpy(it.addressOf(0), bytes, length)
    }

    return result
}

/**
 * Executes the given block function on this resource and then releases it correctly whether an
 * exception is thrown or not.
 */
internal inline fun <T : CPointed, R> CPointer<T>.use(block: (CPointer<T>) -> R): R {
    try {
        return block(this)
    } finally {
        CFBridgingRelease(this)
    }
}

@Suppress("KDocMissingDocumentation")
public class DarwinHttpRequestException(public val origin: NSError) : IOException("Exception in http request: $origin")
