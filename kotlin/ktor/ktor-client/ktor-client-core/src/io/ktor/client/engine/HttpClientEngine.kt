package io.ktor.client.engine

import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.experimental.*
import java.io.*

/**
 * Base interface use to define engines for [HttpClient].
 */
interface HttpClientEngine : Closeable {

    /**
     * [CoroutineDispatcher] specified for io operations.
     */
    val dispatcher: CoroutineDispatcher

    /**
     * Creates a new [HttpClientCall] specific for this engine, using a request [data].
     */
    suspend fun execute(call: HttpClientCall, data: HttpRequestData): HttpEngineCall
}

/**
 * Factory of [HttpClientEngine] with a specific [T] of [HttpClientEngineConfig].
 */
interface HttpClientEngineFactory<out T : HttpClientEngineConfig> {
    /**
     * Creates a new [HttpClientEngine] optionally specifying a [block] configuring [T].
     */
    fun create(block: T.() -> Unit = {}): HttpClientEngine
}

/**
 * Creates a new [HttpClientEngineFactory] based on this one
 * with further configurations from the [nested] block.
 */
fun <T : HttpClientEngineConfig> HttpClientEngineFactory<T>.config(nested: T.() -> Unit): HttpClientEngineFactory<T> {
    val parent = this

    return object : HttpClientEngineFactory<T> {
        override fun create(block: T.() -> Unit): HttpClientEngine = parent.create {
            nested()
            block()
        }
    }
}
