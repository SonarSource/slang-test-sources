package io.ktor.features

import io.ktor.application.*
import io.ktor.util.*
import java.lang.reflect.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*

class DataConversion(private val converters: Map<Type, ConversionService>) : ConversionService {
    override fun fromValues(values: List<String>, type: Type): Any? {
        val converter = converters[type] ?: DefaultConversionService
        return converter.fromValues(values, type)
    }

    override fun toValues(value: Any?): List<String> {
        val type: Type = value?.javaClass ?: return listOf()
        val converter = converters[type] ?: DefaultConversionService
        return converter.toValues(value)
    }

    class Configuration {
        internal val converters = mutableMapOf<Type, ConversionService>()

        fun convert(klass: KClass<*>, convertor: ConversionService) {
            converters.put(klass.java, convertor)
        }

        fun convert(ktype: KType, convertor: ConversionService) {
            converters.put(ktype.javaType, convertor)
        }

        fun convert(klass: KClass<*>, configure: DelegatingConversionService.() -> Unit) {
            convert(klass, DelegatingConversionService(klass).apply(configure))
        }

        inline fun <reified T> convert(noinline configure: DelegatingConversionService.() -> Unit) = convert(T::class, configure)
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Configuration, DataConversion> {
        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): DataConversion {
            val configuration = Configuration().apply(configure)
            return DataConversion(configuration.converters)
        }

        override val key = AttributeKey<DataConversion>("DataConversion")
    }
}

class DelegatingConversionService(private val klass: KClass<*>) : ConversionService {
    private var decoder: ((values: List<String>, type: Type) -> Any?)? = null
    private var encoder: ((value: Any?) -> List<String>)? = null

    fun decode(converter: (values: List<String>, type: Type) -> Any?) {
        if (decoder != null) throw IllegalStateException("Decoder has already been set for type '$klass'")
        decoder = converter
    }

    fun encode(converter: (value: Any?) -> List<String>) {
        if (encoder != null) throw IllegalStateException("Encoder has already been set for type '$klass'")
        encoder = converter
    }

    override fun fromValues(values: List<String>, type: Type): Any? {
        val decoder = decoder ?: throw DataConversionException("Decoder was not specified for class '$klass'")
        return decoder(values, type)
    }

    override fun toValues(value: Any?): List<String> {
        val encoder = encoder ?: throw DataConversionException("Encoder was not specified for class '$klass'")
        return encoder(value)
    }
}

val ApplicationCallPipeline.conversionService: ConversionService
    get() = featureOrNull(DataConversion) ?: DefaultConversionService