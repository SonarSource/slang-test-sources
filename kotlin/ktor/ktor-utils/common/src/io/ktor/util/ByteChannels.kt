/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.util

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*

private const val CHUNK_BUFFER_SIZE = 4096L

/**
 * Split source [ByteReadChannel] into 2 new one.
 * Cancel of one channel in split(input or both outputs) cancels other channels.
 */
public fun ByteReadChannel.split(coroutineScope: CoroutineScope): Pair<ByteReadChannel, ByteReadChannel> {
    val first = ByteChannel(autoFlush = true)
    val second = ByteChannel(autoFlush = true)

    coroutineScope.launch {
        try {
            while (!isClosedForRead) {
                this@split.readRemaining(CHUNK_BUFFER_SIZE).use { chunk ->
                    listOf(
                        async { first.writePacket(chunk.copy()) },
                        async { second.writePacket(chunk.copy()) }
                    ).awaitAll()
                }
            }

            closedCause?.let { throw it }
        } catch (cause: Throwable) {
            this@split.cancel(cause)
            first.cancel(cause)
            second.cancel(cause)
        } finally {
            first.close()
            second.close()
        }
    }.invokeOnCompletion {
        it ?: return@invokeOnCompletion
        first.cancel(it)
        second.cancel(it)
    }

    return first to second
}

/**
 * Copy source channel to both output channels chunk by chunk.
 */
@OptIn(DelicateCoroutinesApi::class)
public fun ByteReadChannel.copyToBoth(first: ByteWriteChannel, second: ByteWriteChannel) {
    GlobalScope.launch(Dispatchers.Unconfined) {
        try {
            while (!isClosedForRead && (!first.isClosedForWrite || !second.isClosedForWrite)) {
                readRemaining(CHUNK_BUFFER_SIZE).use {
                    try {
                        first.writePacket(it.copy())
                        second.writePacket(it.copy())
                    } catch (cause: Throwable) {
                        this@copyToBoth.cancel(cause)
                        first.close(cause)
                        second.close(cause)
                    }
                }
            }

            closedCause?.let { throw it }
        } catch (cause: Throwable) {
            first.close(cause)
            second.close(cause)
        } finally {
            first.close()
            second.close()
        }
    }.invokeOnCompletion {
        it ?: return@invokeOnCompletion
        first.close(it)
        second.close(it)
    }
}

/**
 * Read channel to byte array.
 */
public suspend fun ByteReadChannel.toByteArray(): ByteArray = readRemaining().readBytes()
