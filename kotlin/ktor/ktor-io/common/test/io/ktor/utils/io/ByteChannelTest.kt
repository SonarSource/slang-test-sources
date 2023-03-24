/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io

import io.ktor.test.dispatcher.*
import io.ktor.utils.io.bits.*
import kotlinx.coroutines.*
import kotlin.test.*

class ByteChannelTest {

    @Test
    fun testPeekToFromEmptyToEmpty() = testSuspend {
        val empty = ByteChannel()
        empty.close()

        withMemory(1024) {
            empty.peekTo(it, 0)
        }
    }

    @Test
    fun testPeekToMemoryLessThanContent() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(2048) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, 0, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1024, total)
    }

    @Test
    fun testPeekToMemoryLargerThanContent() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(4096) {
            channel.peekTo(it, 0, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1024, total)
    }

    @Test
    fun testPeekToMemoryEqualsToContent() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, 0, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1024, total)
    }

    @Test
    fun testPeekToWithMax() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, 0, min = 1, max = 16)
        }
        assertEquals(16, total)
    }

    @Test
    fun testPeekToWithMin() = testSuspend {
        val channel = ByteChannel()
        val writeJob = launch {
            repeat(16) {
                channel.writeByte(42)
            }
            channel.flush()
            delay(5000)
            repeat(16) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, 0, min = 16, max = Long.MAX_VALUE)
        }
        assertEquals(16, total)
        writeJob.cancel()
    }

    @Test
    fun testPeekToWithDestinationOffset() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, destinationOffset = 16, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1008L, total)
    }

    @Test
    fun testPeekToWithSourceOffset() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, destinationOffset = 0, offset = 16, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1008L, total)
    }

    @Test
    fun testPeekToWithSourceAndDestinationOffset() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        val total = withMemory(1024) {
            channel.peekTo(it, destinationOffset = 16, offset = 16, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(1008L, total)
    }

    @Test
    fun testPeekToProduceCorrectResult() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(Byte.MAX_VALUE.toInt() + 1) {
                channel.writeByte(it.toByte())
            }
            channel.close()
        }

        withMemory(1024) {
            channel.peekTo(it, destinationOffset = 2, offset = 2, min = 1, max = Long.MAX_VALUE)

            assertEquals(it[0], 0)
            assertEquals(it[1], 0)
            for (i in 2..Byte.MAX_VALUE) {
                assertEquals(it[i], i.toByte())
            }
            for (i in (Byte.MAX_VALUE + 1) until 1024) {
                assertEquals(it[i], 0)
            }
        }
    }

    @Test
    fun testPeekToDoNotMovePosition() = testSuspend {
        val channel = ByteChannel()
        launch {
            repeat(1024) {
                channel.writeByte(42)
            }
            channel.close()
        }

        withMemory(1024) {
            channel.peekTo(it, destinationOffset = 1, offset = 1, min = 1, max = Long.MAX_VALUE)
        }
        assertEquals(channel.totalBytesRead, 0)
    }

    @Test
    fun testReadByteWithTimeout() = testSuspend {
        val channel = ByteChannel()

        launch {
            channel.writeByte(1)
            delay(1000)
            channel.writeByte(0)
            channel.flush()
        }

        var timedOut = false
        try {
            withTimeout(500) {
                channel.readByte()
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }

        assertTrue(timedOut)
        assertEquals(1, channel.readByte())
    }

    @Test
    fun testReadIntWithTimeout() = testSuspend {
        val channel = ByteChannel()

        launch {
            channel.writeInt(1)
            delay(1000)
            channel.writeInt(0)
            channel.flush()
        }

        var timedOut = false
        try {
            withTimeout(500) {
                channel.readInt()
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }

        assertTrue(timedOut)
        assertEquals(1, channel.readInt())
    }

    @Test
    fun testWriteByteWithTimeout() = testSuspend {
        val channel = ByteChannel()

        var timedOut = false
        try {
            withTimeout(500) {
                channel.writeFully(ByteArray(4088))
                channel.writeByte(1)
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }

        assertTrue(timedOut)

        channel.readByte()
        channel.writeByte(42)

        channel.readFully(ByteArray(4087))
        channel.flush()
        assertEquals(42, channel.readByte())
    }
}
