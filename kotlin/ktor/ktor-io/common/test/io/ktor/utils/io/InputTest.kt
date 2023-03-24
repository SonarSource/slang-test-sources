package io.ktor.utils.io

import io.ktor.utils.io.bits.Memory
import io.ktor.utils.io.bits.set
import io.ktor.utils.io.bits.storeIntAt
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.pool.*
import kotlin.test.*

class InputTest {
    private val pool = VerifyingChunkBufferPool()

    @AfterTest
    fun verify() {
        pool.assertEmpty()
    }

    @Test
    fun smokeTest() {
        var closed = false
        var written = false

        val input = object : Input(pool = pool) {
            override fun fill(destination: Memory, offset: Int, length: Int): Int {
                if (written) return 0
                written = true
                destination.storeIntAt(offset, 0x74657374) // = test
                return 4
            }

            override fun closeSource() {
                closed = true
            }
        }

        val text = input.use {
            input.readBytes()
        }

        assertEquals(true, closed, "Should be closed")
        assertEquals("test", String(text), "Content read")
    }

    @Test
    fun testCopy() {
        val items = arrayListOf(
            "test.",
            "123.",
            "zxc."
        )

        val input = object : Input(pool = pool) {
            override fun fill(): ChunkBuffer? {
                if (items.isEmpty()) return null
                return super.fill()
            }

            override fun fill(destination: Memory, offset: Int, length: Int): Int {
                if (items.isEmpty()) return 0
                val next = items.removeAt(0)
                for (index in 0 until next.length) {
                    destination[offset + index] = next[index].code.toByte()
                }
                return next.length
            }

            override fun closeSource() {
                items.clear()
            }
        }

        val out = BytePacketBuilder(pool = pool)
        input.copyTo(out)
        assertEquals("test.123.zxc.", out.build().readText())
    }

    @Test
    fun testReadMoreBytesThenExists() {
        assertFailsWith<EOFException> { ByteReadPacket.Empty.readTextExactBytes(1) }
        assertFailsWith<EOFException> { buildPacket { writeByte(1) }.readTextExactBytes(2) }
    }
}
