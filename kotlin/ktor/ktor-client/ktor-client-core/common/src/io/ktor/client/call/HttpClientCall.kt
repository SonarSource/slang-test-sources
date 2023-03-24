/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.call

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.concurrent.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.reflect.*

/**
 * A pair of a [request] and [response] for a specific [HttpClient].
 *
 * @property client the client that executed the call.
 */
public open class HttpClientCall(
    public val client: HttpClient
) : CoroutineScope {
    private val received: AtomicBoolean = atomic(false)

    override val coroutineContext: CoroutineContext get() = response.coroutineContext

    /**
     * Typed [Attributes] associated to this call serving as a lightweight container.
     */
    public val attributes: Attributes get() = request.attributes

    /**
     * The [request] sent by the client.
     */
    public lateinit var request: HttpRequest
        protected set

    /**
     * The [response] sent by the server.
     */
    public lateinit var response: HttpResponse
        protected set

    @InternalAPI
    public constructor(
        client: HttpClient,
        requestData: HttpRequestData,
        responseData: HttpResponseData
    ) : this(client) {
        this.request = DefaultHttpRequest(this, requestData)
        this.response = DefaultHttpResponse(this, responseData)

        if (responseData.body !is ByteReadChannel) {
            @Suppress("DEPRECATION_ERROR")
            attributes.put(CustomResponse, responseData.body)
        }
    }

    protected open val allowDoubleReceive: Boolean = false

    @OptIn(InternalAPI::class)
    protected open suspend fun getResponseContent(): ByteReadChannel = response.content

    /**
     * Tries to receive the payload of the [response] as a specific expected type provided in [info].
     * Returns [response] if [info] corresponds to [HttpResponse].
     *
     * @throws NoTransformationFoundException If no transformation is found for the type [info].
     * @throws DoubleReceiveException If already called [body].
     */
    @OptIn(InternalAPI::class)
    public suspend fun bodyNullable(info: TypeInfo): Any? {
        try {
            if (response.instanceOf(info.type)) return response
            if (!allowDoubleReceive && !received.compareAndSet(false, true)) {
                throw DoubleReceiveException(this)
            }

            @Suppress("DEPRECATION_ERROR")
            val responseData = attributes.getOrNull(CustomResponse) ?: getResponseContent()

            val subject = HttpResponseContainer(info, responseData)
            val result = client.responsePipeline.execute(this, subject).response.takeIf { it != NullBody }

            if (result != null && !result.instanceOf(info.type)) {
                val from = result::class
                val to = info.type
                throw NoTransformationFoundException(response, from, to)
            }

            return result
        } catch (cause: Throwable) {
            response.cancel("Receive failed", cause)
            throw cause
        } finally {
            response.complete()
        }
    }

    /**
     * Tries to receive the payload of the [response] as a specific expected type provided in [info].
     * Returns [response] if [info] corresponds to [HttpResponse].
     *
     * @throws NoTransformationFoundException If no transformation is found for the type [info].
     * @throws DoubleReceiveException If already called [body].
     * @throws NullPointerException If content is `null`.
     */
    @OptIn(InternalAPI::class)
    public suspend fun body(info: TypeInfo): Any = bodyNullable(info)!!

    override fun toString(): String = "HttpClientCall[${request.url}, ${response.status}]"

    internal fun setResponse(response: HttpResponse) {
        this.response = response
    }

    internal fun setRequest(request: HttpRequest) {
        this.request = request
    }

    public companion object {
        /**
         * [CustomResponse] key used to process the response of custom type in case of [HttpClientEngine] can't return body bytes directly.
         * If present, attribute value will be an initial value for [HttpResponseContainer] in [HttpClient.responsePipeline].
         *
         * Example: [WebSocketSession]
         */
        @Deprecated(
            "This is going to be removed. Please file a ticket with clarification why and what for do you need it.",
            level = DeprecationLevel.ERROR
        )
        public val CustomResponse: AttributeKey<Any> = AttributeKey("CustomResponse")
    }
}

/**
 * Tries to receive the payload of the [response] as a specific type [T].
 *
 * @throws NoTransformationFoundException If no transformation is found for the type [T].
 * @throws DoubleReceiveException If already called [body].
 */
public suspend inline fun <reified T> HttpClientCall.body(): T = bodyNullable(typeInfo<T>()) as T

/**
 * Tries to receive the payload of the [response] as a specific type [T].
 *
 * @throws NoTransformationFoundException If no transformation is found for the type [T].
 * @throws DoubleReceiveException If already called [body].
 */
public suspend inline fun <reified T> HttpResponse.body(): T = call.bodyNullable(typeInfo<T>()) as T

/**
 * Tries to receive the payload of the [response] as a specific type [T] described in [typeInfo].
 *
 * @throws NoTransformationFoundException If no transformation is found for the type info [typeInfo].
 * @throws DoubleReceiveException If already called [body].
 */
@Suppress("UNCHECKED_CAST")
public suspend fun <T> HttpResponse.body(typeInfo: TypeInfo): T = call.bodyNullable(typeInfo) as T

/**
 * Exception representing that the response payload has already been received.
 */
@Suppress("KDocMissingDocumentation")
public class DoubleReceiveException(call: HttpClientCall) : IllegalStateException() {
    override val message: String = "Response already received: $call"
}

/**
 * Exception representing fail of the response pipeline
 * [cause] contains origin pipeline exception
 */
@Suppress("KDocMissingDocumentation", "unused")
public class ReceivePipelineException(
    public val request: HttpClientCall,
    public val info: TypeInfo,
    override val cause: Throwable
) : IllegalStateException("Fail to run receive pipeline: $cause")

/**
 * Exception representing the no transformation was found.
 * It includes the received type and the expected type as part of the message.
 */
@Suppress("KDocMissingDocumentation")
public class NoTransformationFoundException(
    response: HttpResponse,
    from: KClass<*>,
    to: KClass<*>
) : UnsupportedOperationException() {
    override val message: String? = """No transformation found: $from -> $to
        |with response from ${response.request.url}:
        |status: ${response.status}
        |response headers: 
        |${response.headers.flattenEntries().joinToString { (key, value) -> "$key: $value\n" }}
    """.trimMargin()
}
