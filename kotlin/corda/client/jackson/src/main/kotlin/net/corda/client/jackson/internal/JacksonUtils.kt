package net.corda.client.jackson.internal

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.module.kotlin.convertValue

inline fun JsonGenerator.jsonObject(fieldName: String? = null, gen: JsonGenerator.() -> Unit) {
    fieldName?.let { writeFieldName(it) }
    writeStartObject()
    gen()
    writeEndObject()
}

inline fun <reified T> JsonParser.readValueAs(): T = readValueAs(T::class.java)

inline fun <reified T : Any> JsonNode.valueAs(mapper: ObjectMapper): T = mapper.convertValue(this)

inline fun <reified T : Any> JsonNode.childrenAs(mapper: ObjectMapper): List<T> {
    return elements().asSequence().map { it.valueAs<T>(mapper) }.toList()
}

@JacksonAnnotationsInside
@JsonSerialize(using = ToStringSerializer::class)
annotation class ToStringSerialize

abstract class SimpleDeserializer<T>(private val func: JsonParser.() -> T) : JsonDeserializer<T>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): T = func(parser)
}
