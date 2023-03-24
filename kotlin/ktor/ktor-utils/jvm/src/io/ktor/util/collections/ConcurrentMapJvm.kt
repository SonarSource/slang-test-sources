// ktlint-disable filename
/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util.collections

import java.util.concurrent.*

/**
 * Ktor concurrent map implementation. Please do not use it.
 */
public actual class ConcurrentMap<Key, Value> public actual constructor(initialCapacity: Int) : MutableMap<Key, Value> {
    private val delegate = ConcurrentHashMap<Key, Value>(initialCapacity)

    /**
     * Computes [block] and inserts result in map. The [block] will be evaluated at most once.
     */
    public actual fun computeIfAbsent(key: Key, block: () -> Value): Value = delegate.computeIfAbsent(key) {
        block()
    }

    override val size: Int
        get() = delegate.size

    override fun containsKey(key: Key): Boolean = delegate.containsKey(key)

    override fun containsValue(value: Value): Boolean = delegate.containsValue(value)

    override fun get(key: Key): Value? = delegate[key]

    override fun isEmpty(): Boolean = delegate.isEmpty()

    override val entries: MutableSet<MutableMap.MutableEntry<Key, Value>>
        get() = delegate.entries

    override val keys: MutableSet<Key>
        get() = delegate.keys

    override val values: MutableCollection<Value>
        get() = delegate.values

    override fun clear() {
        delegate.clear()
    }

    override fun put(key: Key, value: Value): Value? = delegate.put(key, value)

    override fun putAll(from: Map<out Key, Value>) {
        delegate.putAll(from)
    }

    override fun remove(key: Key): Value? = delegate.remove(key)

    actual override fun remove(key: Key, value: Value): Boolean = delegate.remove(key, value)

    override fun hashCode(): Int = delegate.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other !is Map<*, *>) return false
        return other == delegate
    }

    override fun toString(): String = "ConcurrentMapJvm by $delegate"
}
