package io.ktor.client.call

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import java.io.*
import java.util.concurrent.atomic.*
import kotlin.reflect.*

/**
 * A class that represents a single pair of [request] and [response] for a specific [HttpClient].
 */
class HttpClientCall internal constructor(
    private val client: HttpClient
) : Closeable {
    private val received = AtomicBoolean(false)

    /**
     * Represents the [request] sent by the client.
     */
    lateinit var request: HttpRequest
        internal set

    /**
     * Represents the [response] sent by the server.
     */
    lateinit var response: HttpResponse
        internal set

    /**
     * Tries to receive the payload of the [response] as an specific [expectedType].
     * Returns [response] if [expectedType] is [HttpResponse].
     *
     * @throws NoTransformationFound If no transformation is found for the [expectedType].
     * @throws DoubleReceiveException If already called [receive].
     */
    suspend fun receive(expectedType: KClass<*>): Any {
        if (expectedType.isInstance(response)) return response
        if (!received.compareAndSet(false, true)) throw DoubleReceiveException(this)

        val subject = HttpResponseContainer(expectedType, response)
        val result = client.responsePipeline.execute(this, subject).response

        if (!expectedType.isInstance(result)) throw NoTransformationFound(result::class, expectedType)
        return result
    }

    /**
     * Closes the underlying [response].
     */
    override fun close() {
        response.close()
    }
}

data class HttpEngineCall(val request: HttpRequest, val response: HttpResponse)

/**
 * Constructs a [HttpClientCall] from this [HttpClient] and with the specified [HttpRequestBuilder]
 * configured inside the [block].
 */
suspend fun HttpClient.call(block: HttpRequestBuilder.() -> Unit = {}): HttpClientCall =
    execute(HttpRequestBuilder().apply(block))

/**
 * Tries to receive the payload of the [response] as an specific type [T].
 *
 * @throws NoTransformationFound If no transformation is found for the type [T].
 * @throws DoubleReceiveException If already called [receive].
 */
suspend inline fun <reified T> HttpClientCall.receive(): T = receive(T::class) as T

/**
 * Exception representing that the response payload has already been received.
 */
class DoubleReceiveException(call: HttpClientCall) : IllegalStateException() {
    override val message: String = "Request already received: $call"
}

/**
 * Exception representing the no transformation was found.
 * It includes the received type and the expected type as part of the message.
 */
class NoTransformationFound(from: KClass<*>, to: KClass<*>) : UnsupportedOperationException() {
    override val message: String? = "No transformation found: $from -> $to"
}
