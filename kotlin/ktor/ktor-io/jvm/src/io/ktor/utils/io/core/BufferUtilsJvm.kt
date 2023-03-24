package io.ktor.utils.io.core

import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.internal.jvm.*
import io.ktor.utils.io.pool.*
import java.nio.*
import kotlin.contracts.*

public fun ChunkBuffer(buffer: ByteBuffer, pool: ObjectPool<ChunkBuffer>? = null): ChunkBuffer =
    ChunkBuffer(Memory.of(buffer), null, pool)

/**
 * Apply [block] function on a [ByteBuffer] of readable bytes.
 * The [block] function should return number of consumed bytes.
 * @return number of bytes consumed
 */
public inline fun ChunkBuffer.readDirect(block: (ByteBuffer) -> Unit): Int {
    val readPosition = readPosition
    val writePosition = writePosition
    val bb = memory.buffer.duplicate()!!
    bb.limit(writePosition)
    bb.position(readPosition)

    block(bb)

    val delta = bb.position() - readPosition
    if (delta < 0) negativeShiftError(delta)
    if (bb.limit() != writePosition) limitChangeError()
    discardExact(delta)

    return delta
}

/**
 * Apply [block] function on a [ByteBuffer] of the free space.
 * The [block] function should return number of written bytes.
 * @return number of bytes written
 */
public inline fun ChunkBuffer.writeDirect(size: Int, block: (ByteBuffer) -> Unit): Int {
    val rem = writeRemaining
    require(size <= rem) { "size $size is greater than buffer's remaining capacity $rem" }
    val buffer = memory.buffer.duplicate()!!
    val writePosition = writePosition
    val limit = limit
    buffer.limit(limit)
    buffer.position(writePosition)

    block(buffer)

    val delta = buffer.position() - writePosition
    if (delta < 0 || delta > rem) wrongBufferPositionChangeError(delta, size)

    commitWritten(delta)

    return delta
}

/**
 * Reset read/write position to original's content pos/limit. May not work due to slicing.
 */
internal fun ChunkBuffer.resetFromContentToWrite(child: ByteBuffer) {
    resetForWrite(child.limit())
    commitWrittenUntilIndex(child.position())
}

public fun Buffer.readFully(dst: ByteBuffer, length: Int) {
    readExact(length, "buffer content") { memory, offset ->
        val limit = dst.limit()
        try {
            dst.limit(dst.position() + length)
            memory.copyTo(dst, offset)
        } finally {
            dst.limit(limit)
        }
    }
}

public fun Buffer.readAvailable(dst: ByteBuffer, length: Int = dst.remaining()): Int {
    if (!canRead()) return -1
    val size = minOf(readRemaining, length)
    readFully(dst, size)
    return size
}

@OptIn(ExperimentalContracts::class)
public inline fun Buffer.readDirect(block: (ByteBuffer) -> Unit): Int {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    return read { memory, start, endExclusive ->
        val nioBuffer = memory.slice(start, endExclusive - start).buffer
        block(nioBuffer)
        check(nioBuffer.limit() == endExclusive - start) { "Buffer's limit change is not allowed" }

        nioBuffer.position()
    }
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalContracts::class)
public inline fun Buffer.writeDirect(size: Int = 1, block: (ByteBuffer) -> Unit): Int {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    return write { memory, start, endExclusive ->
        val nioBuffer = memory.slice(start, endExclusive - start).buffer
        block(nioBuffer)
        check(nioBuffer.limit() == endExclusive - start) { "Buffer's limit change is not allowed" }

        nioBuffer.position()
    }
}
