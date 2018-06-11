package io.ktor.util

import java.util.concurrent.*

/**
 * Specifies a key for an attribute in [Attributes]
 * @param T is type of the value stored in the attribute
 * @param name is a name of the attribute for diagnostic purposes
 */
class AttributeKey<T>(val name: String) {
    override fun toString(): String = if (name.isEmpty())
        super.toString()
    else
        "AttributeKey: $name"
}

/**
 * Map of attributes accessible by [AttributeKey] in a typed manner
 */
class Attributes {
    private val map = ConcurrentHashMap<AttributeKey<*>, Any?>()

    /**
     * Gets a value of the attribute for the specified [key], or throws an exception if an attribute doesn't exist
     */
    operator fun <T : Any> get(key: AttributeKey<T>): T = getOrNull(key) ?: throw IllegalStateException("No instance for key $key")

    /**
     * Gets a value of the attribute for the specified [key], or return `null` if an attribute doesn't exist
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(key: AttributeKey<T>): T? = map[key] as T?

    /**
     * Checks if an attribute with the specified [key] exists
     */
    operator fun contains(key: AttributeKey<*>) = map.containsKey(key)

    /**
     * Creates or changes an attribute with the specified [key] using [value]
     */
    fun <T : Any> put(key: AttributeKey<T>, value: T) {
        map[key] = value
    }

    /**
     * Removes an attribute with the specified [key]
     */
    fun <T : Any> remove(key: AttributeKey<T>) {
        map.remove(key)
    }

    /**
     * Removes an attribute with the specified [key] and returns its current value, throws an exception if an attribute doesn't exist
     */
    fun <T : Any> take(key: AttributeKey<T>): T {
        return get(key).also { map.remove(key) }
    }

    /**
     * Removes an attribute with the specified [key] and returns its current value, returns `null` if an attribute doesn't exist
     */
    fun <T : Any> takeOrNull(key: AttributeKey<T>): T? {
        return getOrNull(key).also { map.remove(key) }
    }

    /**
     * Gets a value of the attribute for the specified [key], or calls supplied [block] to compute its value
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> computeIfAbsent(key: AttributeKey<T>, block: () -> T): T {
        return map.computeIfAbsent(key) { block() } as T
    }

    /**
     * Returns [List] of all [AttributeKey] instances in this map
     */
    val allKeys: List<AttributeKey<*>>
        get() = map.keys.toList()
}
