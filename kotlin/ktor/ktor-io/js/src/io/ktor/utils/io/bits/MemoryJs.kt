@file:Suppress("NOTHING_TO_INLINE")

package io.ktor.utils.io.bits

import io.ktor.utils.io.core.internal.*
import org.khronos.webgl.*
import kotlin.require

/**
 * Represents a linear range of bytes.
 */
public actual class Memory constructor(public val view: DataView) {
    /**
     * Size of memory range in bytes.
     */
    public actual inline val size: Long get() = view.byteLength.toLong()

    /**
     * Size of memory range in bytes represented as signed 32bit integer
     * @throws IllegalStateException when size doesn't fit into a signed 32bit integer
     */
    public actual inline val size32: Int get() = view.byteLength

    /**
     * Returns byte at [index] position.
     */
    public actual inline fun loadAt(index: Int): Byte {
        return view.getInt8(index)
    }

    /**
     * Returns byte at [index] position.
     */
    public actual inline fun loadAt(index: Long): Byte {
        return view.getInt8(index.toIntOrFail("index"))
    }

    /**
     * Write [value] at the specified [index].
     */
    public actual inline fun storeAt(index: Int, value: Byte) {
        view.setInt8(index, value)
    }

    /**
     * Write [value] at the specified [index]
     */
    public actual inline fun storeAt(index: Long, value: Byte) {
        view.setInt8(index.toIntOrFail("index"), value)
    }

    /**
     * Returns memory's subrange. On some platforms it could do range checks but it is not guaranteed to be safe.
     * It also could lead to memory allocations on some platforms.
     */
    public actual fun slice(offset: Int, length: Int): Memory {
        require(offset >= 0) { "offset shouldn't be negative: $offset" }
        require(length >= 0) { "length shouldn't be negative: $length" }
        if (offset + length > size) {
            throw IndexOutOfBoundsException("offset + length > size: $offset + $length > $size")
        }

        return Memory(
            DataView(
                view.buffer,
                view.byteOffset + offset,
                length
            )
        )
    }

    /**
     * Returns memory's subrange. On some platforms it could do range checks but it is not guaranteed to be safe.
     * It also could lead to memory allocations on some platforms.
     */
    public actual fun slice(offset: Long, length: Long): Memory {
        return slice(offset.toIntOrFail("offset"), length.toIntOrFail("length"))
    }

    /**
     * Copies bytes from this memory range from the specified [offset] and [length]
     * to the [destination] at [destinationOffset].
     * Copying bytes from a memory to itself is allowed.
     */
    public actual fun copyTo(
        destination: Memory,
        offset: Int,
        length: Int,
        destinationOffset: Int
    ) {
        val src = Int8Array(view.buffer, view.byteOffset + offset, length)
        val dst = Int8Array(destination.view.buffer, destination.view.byteOffset + destinationOffset, length)

        dst.set(src)
    }

    /**
     * Copies bytes from this memory range from the specified [offset] and [length]
     * to the [destination] at [destinationOffset].
     * Copying bytes from a memory to itself is allowed.
     */
    public actual fun copyTo(
        destination: Memory,
        offset: Long,
        length: Long,
        destinationOffset: Long
    ) {
        copyTo(
            destination,
            offset.toIntOrFail("offset"),
            length.toIntOrFail("length"),
            destinationOffset.toIntOrFail("destinationOffset")
        )
    }

    public actual companion object {
        /**
         * Represents an empty memory region
         */
        public actual val Empty: Memory = Memory(DataView(ArrayBuffer(0)))
    }
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public actual fun Memory.copyTo(
    destination: ByteArray,
    offset: Int,
    length: Int,
    destinationOffset: Int
) {
    @Suppress("UnsafeCastFromDynamic")
    val to: Int8Array = destination.asDynamic()

    val from = Int8Array(view.buffer, view.byteOffset + offset, length)

    to.set(from, destinationOffset)
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public actual fun Memory.copyTo(
    destination: ByteArray,
    offset: Long,
    length: Int,
    destinationOffset: Int
) {
    copyTo(destination, offset.toIntOrFail("offset"), length, destinationOffset)
}

/**
 * Fill memory range starting at the specified [offset] with [value] repeated [count] times.
 */
public actual fun Memory.fill(offset: Int, count: Int, value: Byte) {
    for (index in offset until offset + count) {
        this[index] = value
    }
}

/**
 * Fill memory range starting at the specified [offset] with [value] repeated [count] times.
 */
public actual fun Memory.fill(offset: Long, count: Long, value: Byte) {
    fill(offset.toIntOrFail("offset"), count.toIntOrFail("count"), value)
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public fun Memory.copyTo(destination: ArrayBuffer, offset: Int, length: Int, destinationOffset: Int) {
    @Suppress("UnsafeCastFromDynamic")
    val to = Int8Array(destination, destinationOffset, length)
    val from = Int8Array(view.buffer, view.byteOffset + offset, length)

    to.set(from, 0)
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public fun Memory.copyTo(destination: ArrayBufferView, offset: Int, length: Int, destinationOffset: Int) {
    @Suppress("UnsafeCastFromDynamic")
    val to = Int8Array(destination.buffer, destinationOffset + destination.byteOffset, length)
    val from = Int8Array(view.buffer, view.byteOffset + offset, length)

    to.set(from, 0)
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public fun ArrayBuffer.copyTo(destination: Memory, offset: Int, length: Int, destinationOffset: Int) {
    val from = Int8Array(this, offset, length)
    val to = Int8Array(destination.view.buffer, destination.view.byteOffset + destinationOffset, length)

    to.set(from, 0)
}

/**
 * Copies bytes from this memory range from the specified [offset] and [length]
 * to the [destination] at [destinationOffset].
 */
public fun ArrayBufferView.copyTo(destination: Memory, offset: Int, length: Int, destinationOffset: Int) {
    buffer.copyTo(destination, offset + byteOffset, length, destinationOffset)
}

internal val Memory.Int8ArrayView: Int8Array get() = Int8Array(view.buffer, view.byteOffset, view.byteLength)
