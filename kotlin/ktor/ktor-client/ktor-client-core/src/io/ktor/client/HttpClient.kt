package io.ktor.client

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import java.io.*

/**
 * Asynchronous client to perform HTTP requests.
 *
 * This is a generic implementation that uses a specific engine [HttpClientEngine].
 */
class HttpClient private constructor(
    private val engine: HttpClientEngine,
    block: suspend HttpClientConfig.() -> Unit = {}
) : Closeable {

    /**
     * Constructs an asynchronous [HttpClient] using the specified [engineFactory]
     * and an optional [block] for configuring this client.
     */
    constructor(
        engineFactory: HttpClientEngineFactory<*>,
        block: suspend HttpClientConfig.() -> Unit = {}
    ) : this(engineFactory.create(), block)

    /**
     * Pipeline used for processing all the requests sent by this client.
     */
    val requestPipeline = HttpRequestPipeline().apply {
        // default send scenario
        intercept(HttpRequestPipeline.Send) { content ->
            proceedWith(sendPipeline.execute(context, content))
        }
    }

    /**
     * Pipeline used for processing all the responses sent by the server.
     */
    val responsePipeline = HttpResponsePipeline()

    /**
     * Pipeline used for sending the request
     */
    val sendPipeline = HttpSendPipeline().apply {
        intercept(HttpSendPipeline.Engine) { content ->
            val call = HttpClientCall(this@HttpClient)
            val requestData = HttpRequestBuilder().apply {
                takeFrom(context)
                body = content
            }.build()

            val (request, response) = engine.execute(call, requestData)
            call.request = request
            call.response = response

            val receivedCall = receivePipeline.execute(call, call.response).call
            proceedWith(receivedCall)
        }
    }

    /**
     * Pipeline used for receiving request
     */
    val receivePipeline = HttpReceivePipeline()

    /**
     * Typed attributes used as a lightweight container for this client.
     */
    val attributes = Attributes()

    /**
     * Dispatcher handles io operations
     */
    val dispatcher: CoroutineDispatcher = engine.dispatcher

    private val config = HttpClientConfig()

    init {
        runBlocking {
            config.install(HttpPlainText)
            config.install(HttpIgnoreBody)
            config.install("DefaultTransformers") { defaultTransformers() }
            config.block()
        }

        config.install(this)
    }

    /**
     * Creates a new [HttpRequest] from a request [data] and a specific client [call].
     */
    suspend fun execute(builder: HttpRequestBuilder): HttpClientCall =
        requestPipeline.execute(builder, builder.body) as HttpClientCall

    /**
     * Returns a new [HttpClient] copying this client configuration,
     * and additionally configured by the [block] parameter.
     */
    fun config(block: suspend HttpClientConfig.() -> Unit): HttpClient = HttpClient(engine, block)

    /**
     * Closes the underlying [engine].
     */
    override fun close() {
        engine.close()

        attributes.allKeys.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val feature = attributes[key as AttributeKey<Any>]

            if (feature is AutoCloseable) {
                feature.close()
            }
        }
    }
}
