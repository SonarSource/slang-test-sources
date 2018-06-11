package net.corda.serialization.internal.amqp.custom

import net.corda.core.serialization.SerializationContext
import net.corda.serialization.internal.amqp.*
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL

object X509CRLSerializer : CustomSerializer.Implements<X509CRL>(X509CRL::class.java) {
    override val schemaForDocumentation = Schema(listOf(RestrictedType(
            type.toString(),
            "",
            listOf(type.toString()),
            SerializerFactory.primitiveTypeName(ByteArray::class.java)!!,
            descriptor,
            emptyList()
    )))

    override fun writeDescribedObject(obj: X509CRL, data: Data, type: Type, output: SerializationOutput,
                                      context: SerializationContext) {
        output.writeObject(obj.encoded, data, clazz, context)
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput,
                            context: SerializationContext): X509CRL {
        val bytes = input.readObject(obj, schemas, ByteArray::class.java, context) as ByteArray
        return CertificateFactory.getInstance("X.509").generateCRL(bytes.inputStream()) as X509CRL
    }
}
