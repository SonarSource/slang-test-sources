package net.corda.serialization.internal.carpenter

import com.google.common.reflect.TypeToken
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.*
import net.corda.serialization.internal.amqp.testutils.TestSerializationOutput
import net.corda.serialization.internal.amqp.testutils.deserialize
import net.corda.serialization.internal.amqp.testutils.serialize
import net.corda.serialization.internal.amqp.testutils.testDefaultFactory
import org.assertj.core.api.Assertions
import org.junit.Test
import java.io.NotSerializableException
import java.lang.reflect.Type
import kotlin.reflect.jvm.jvmName
import kotlin.test.*

// Simple way to ensure we end up trying to carpent a class, "remove" it from the class loader (if only
// actually doing that was simple)
class TestClassLoader(private var exclude: List<String>) : ClassLoader() {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name in exclude) {
            throw ClassNotFoundException("Pretending we can't find class $name")
        }

        return super.loadClass(name, resolve)
    }
}

interface TestInterface {
    fun runThing(): Int
}

// Create a custom serialization factory where we need to be able to both specify a carpenter
// but also have the class loader used by the carpenter be substantially different from the
// one used by the factory so as to ensure we can control their behaviour independently.
class TestFactory(classCarpenter: ClassCarpenter)
    : SerializerFactory(classCarpenter.whitelist, classCarpenter)

class CarpenterExceptionTests {
    companion object {
        val VERBOSE: Boolean get() = false
    }

    @Test
    fun checkClassComparison() {
        class CLA : ClassLoader() {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                println("CLA::loadClass $name")
                return super.loadClass(name, resolve)
            }
        }

        class CLB : ClassLoader() {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                println("CLB::loadClass $name")
                return super.loadClass(name, resolve)
            }
        }

        data class A(val a: Int, val b: Int)

        val a3 = ClassLoader.getSystemClassLoader().loadClass(A::class.java.name)
        val a1 = CLA().loadClass(A::class.java.name)
        val a2 = CLB().loadClass(A::class.java.name)

        assertTrue(TypeToken.of(a1).isSubtypeOf(a2))
        assertTrue(a1 is Type)
        assertTrue(a2 is Type)
        assertTrue(a3 is Type)
        assertEquals(a1, a2)
        assertEquals(a1, a3)
        assertEquals(a2, a3)
    }

    @Test
    fun carpenterExceptionRethrownAsNotSerializableException() {
        data class C2(val i: Int) : TestInterface {
            override fun runThing() = 1
        }

        data class C1(val i: Int, val c: C2)

        // We need two factories to ensure when we deserialize the blob we don't just use the serializer
        // we built to serialise things
        val ser = TestSerializationOutput(VERBOSE, testDefaultFactory()).serialize(C1(1, C2(2)))

        // Our second factory is "special"
        // The class loader given to the factory rejects the outer class, this will trigger an attempt to
        // carpent that class up. However, when looking at the fields specified as properties of that class
        // we set the class loader of the ClassCarpenter to reject one of them, resulting in a CarpentryError
        // which we then  want the code to wrap in a NotSerializeableException
        val cc = ClassCarpenterImpl(TestClassLoader(listOf(C2::class.jvmName)), AllWhitelist)
        val factory = TestFactory(cc)

        Assertions.assertThatThrownBy {
            DeserializationInput(factory).deserialize(ser)
        }.isInstanceOf(NotSerializableException::class.java)
                .hasMessageContaining(C2::class.java.name)
    }
}