package net.corda.core.utilities

import com.esotericsoftware.kryo.KryoException
import net.corda.core.crypto.random63BitValue
import net.corda.core.serialization.*
import net.corda.node.serialization.kryo.KRYO_CHECKPOINT_CONTEXT
import net.corda.node.serialization.kryo.kryoMagic
import net.corda.serialization.internal.SerializationContextImpl
import net.corda.testing.core.SerializationEnvironmentRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

object EmptyWhitelist : ClassWhitelist {
    override fun hasListed(type: Class<*>): Boolean = false
}

class KotlinUtilsTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()
    @JvmField
    @Rule
    val expectedEx: ExpectedException = ExpectedException.none()

    private val KRYO_CHECKPOINT_NOWHITELIST_CONTEXT = SerializationContextImpl(kryoMagic,
            javaClass.classLoader,
            EmptyWhitelist,
            emptyMap(),
            true,
            SerializationContext.UseCase.Checkpoint,
            null)

    @Test
    fun `transient property which is null`() {
        val test = NullTransientProperty()
        test.transientValue
        test.transientValue
        assertThat(test.evalCount).isEqualTo(1)
    }

    @Test
    fun `checkpointing a transient property with non-capturing lambda`() {
        val original = NonCapturingTransientProperty()
        val originalVal = original.transientVal
        val copy = original.serialize(context = KRYO_CHECKPOINT_CONTEXT).deserialize(context = KRYO_CHECKPOINT_CONTEXT)
        val copyVal = copy.transientVal
        assertThat(copyVal).isNotEqualTo(originalVal)
        assertThat(copy.transientVal).isEqualTo(copyVal)
    }

    @Test
    fun `deserialise transient property with non-capturing lambda`() {
        expectedEx.expect(KryoException::class.java)
        expectedEx.expectMessage("is not annotated or on the whitelist, so cannot be used in serialization")
        val original = NonCapturingTransientProperty()
        original.serialize(context = KRYO_CHECKPOINT_CONTEXT).deserialize(context = KRYO_CHECKPOINT_NOWHITELIST_CONTEXT)
    }

    @Test
    fun `checkpointing a transient property with capturing lambda`() {
        val original = CapturingTransientProperty("Hello")
        val originalVal = original.transientVal
        val copy = original.serialize(context = KRYO_CHECKPOINT_CONTEXT).deserialize(context = KRYO_CHECKPOINT_CONTEXT)
        val copyVal = copy.transientVal
        assertThat(copyVal).isNotEqualTo(originalVal)
        assertThat(copy.transientVal).isEqualTo(copyVal)
        assertThat(copy.transientVal).startsWith("Hello")
    }

    @Test
    fun `deserialise transient property with capturing lambda`() {
        expectedEx.expect(KryoException::class.java)
        expectedEx.expectMessage("is not annotated or on the whitelist, so cannot be used in serialization")

        val original = CapturingTransientProperty("Hello")

        original.serialize(context = KRYO_CHECKPOINT_CONTEXT).deserialize(context = KRYO_CHECKPOINT_NOWHITELIST_CONTEXT)
    }

    private class NullTransientProperty {
        var evalCount = 0
        val transientValue by transient {
            evalCount++
            null
        }
    }

    @CordaSerializable
    private class NonCapturingTransientProperty {
        val transientVal by transient { random63BitValue() }
    }

    @CordaSerializable
    private class CapturingTransientProperty(val prefix: String, val seed: Long = random63BitValue()) {
        val transientVal by transient { prefix + seed + random63BitValue() }
    }
}