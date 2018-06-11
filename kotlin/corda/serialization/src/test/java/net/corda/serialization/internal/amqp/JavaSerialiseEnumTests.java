package net.corda.serialization.internal.amqp;

import net.corda.serialization.internal.amqp.testutils.TestSerializationContext;
import org.junit.Test;

import net.corda.serialization.internal.AllWhitelist;
import net.corda.core.serialization.SerializedBytes;

import java.io.NotSerializableException;

public class JavaSerialiseEnumTests {

    public enum Bras {
        TSHIRT, UNDERWIRE, PUSHUP, BRALETTE, STRAPLESS, SPORTS, BACKLESS, PADDED
    }

    private static class Bra {
        private final Bras bra;

        private Bra(Bras bra) {
            this.bra = bra;
        }

        public Bras getBra() {
            return this.bra;
        }
    }

    @Test
    public void testJavaConstructorAnnotations() throws NotSerializableException {
        Bra bra = new Bra(Bras.UNDERWIRE);

        SerializerFactory factory1 = new SerializerFactory(AllWhitelist.INSTANCE, ClassLoader.getSystemClassLoader(),
                new EvolutionSerializerGetter(),
                new SerializerFingerPrinter());
        SerializationOutput ser = new SerializationOutput(factory1);
        SerializedBytes<Object> bytes = ser.serialize(bra, TestSerializationContext.testSerializationContext);
    }
}
