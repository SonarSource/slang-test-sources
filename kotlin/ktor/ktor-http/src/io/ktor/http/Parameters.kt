package io.ktor.http

import io.ktor.util.*

/**
 * Represents HTTP parameters as a map from case-insensitive names to collection of [String] values
 */
interface Parameters : StringValues {
    companion object {
        /**
         * Empty [Parameters] instance
         */
        val Empty: Parameters = EmptyParameters

        /**
         * Builds a [Parameters] instance with the given [builder] function
         * @param builder specifies a function to build a map
         */
        inline fun build(builder: ParametersBuilder.() -> Unit): Parameters = ParametersBuilder().apply(builder).build()
    }

}

class ParametersBuilder(size: Int = 8) : StringValuesBuilder(true, size) {
    override fun build(): Parameters {
        require(!built) { "ParametersBuilder can only build a single Parameters instance" }
        built = true
        return ParametersImpl(values)
    }
}

object EmptyParameters : Parameters {
    override val caseInsensitiveName: Boolean get() = true
    override fun getAll(name: String): List<String>? = null
    override fun names(): Set<String> = emptySet()
    override fun entries(): Set<Map.Entry<String, List<String>>> = emptySet()
    override fun isEmpty(): Boolean = true
    override fun toString() = "Parameters ${entries()}"
}

fun parametersOf(): Parameters = Parameters.Empty
fun parametersOf(name: String, value: String): Parameters = ParametersSingleImpl(name, listOf(value))
fun parametersOf(name: String, values: List<String>): Parameters = ParametersSingleImpl(name, values)
fun parametersOf(vararg pairs: Pair<String, List<String>>): Parameters = ParametersImpl(pairs.asList().toMap())

class ParametersImpl(values: Map<String, List<String>> = emptyMap()) : Parameters, StringValuesImpl(true, values) {
    override fun toString() = "Parameters ${entries()}"
}

class ParametersSingleImpl(name: String, values: List<String>) : Parameters, StringValuesSingleImpl(true, name, values) {
    override fun toString() = "Parameters ${entries()}"
}

operator fun Parameters.plus(other: Parameters) = when {
    caseInsensitiveName == other.caseInsensitiveName -> when {
        this.isEmpty() -> other
        other.isEmpty() -> this
        else -> Parameters.build { appendAll(this@plus); appendAll(other) }
    }
    else -> throw IllegalArgumentException("Cannot concatenate Parameters with case-sensitive and case-insensitive names")
}
