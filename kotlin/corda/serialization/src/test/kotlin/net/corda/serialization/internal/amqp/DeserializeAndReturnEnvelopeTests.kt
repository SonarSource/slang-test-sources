package net.corda.serialization.internal.amqp

import net.corda.core.serialization.CordaSerializable
import net.corda.serialization.internal.amqp.testutils.deserializeAndReturnEnvelope
import net.corda.serialization.internal.amqp.testutils.serialize
import net.corda.serialization.internal.amqp.testutils.testDefaultFactoryNoEvolution
import net.corda.serialization.internal.amqp.testutils.testDefaultFactoryWithWhitelist
import net.corda.serialization.internal.amqp.testutils.testName
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeserializeAndReturnEnvelopeTests {
    // the 'this' reference means we can't just move this to the common test utils
    @Suppress("NOTHING_TO_INLINE")
    private inline fun classTestName(clazz: String) = "${this.javaClass.name}\$${testName()}\$$clazz"

    val factory = testDefaultFactoryNoEvolution()

    @Test
    fun oneType() {
        data class A(val a: Int, val b: String)

        val a = A(10, "20")

        fun serialise(clazz: Any) = SerializationOutput(factory).serialize(clazz)
        val obj = DeserializationInput(factory).deserializeAndReturnEnvelope(serialise(a))

        assertTrue(obj.obj is A)
        assertEquals(1, obj.envelope.schema.types.size)
        assertEquals(classTestName("A"), obj.envelope.schema.types.first().name)
    }

    @Test
    fun twoTypes() {
        data class A(val a: Int, val b: String)
        data class B(val a: A, val b: Float)

        val b = B(A(10, "20"), 30.0F)

        fun serialise(clazz: Any) = SerializationOutput(factory).serialize(clazz)
        val obj = DeserializationInput(factory).deserializeAndReturnEnvelope(serialise(b))

        assertTrue(obj.obj is B)
        assertEquals(2, obj.envelope.schema.types.size)
        assertNotEquals(null, obj.envelope.schema.types.find { it.name == classTestName("A") })
        assertNotEquals(null, obj.envelope.schema.types.find { it.name == classTestName("B") })
    }

    @Test
    fun unannotatedInterfaceIsNotInSchema() {
        @CordaSerializable
        data class Foo(val bar: Int) : Comparable<Foo> {
            override fun compareTo(other: Foo): Int = bar.compareTo(other.bar)
        }

        val a = Foo(123)
        val factory = testDefaultFactoryWithWhitelist()
        fun serialise(clazz: Any) = SerializationOutput(factory).serialize(clazz)
        val obj = DeserializationInput(factory).deserializeAndReturnEnvelope(serialise(a))

        assertTrue(obj.obj is Foo)
        assertEquals(1, obj.envelope.schema.types.size)
        assertNotEquals(null, obj.envelope.schema.types.find { it.name == classTestName("Foo") })
        assertEquals(null, obj.envelope.schema.types.find { it.name == "java.lang.Comparable<${classTestName("Foo")}>" })
    }
}
