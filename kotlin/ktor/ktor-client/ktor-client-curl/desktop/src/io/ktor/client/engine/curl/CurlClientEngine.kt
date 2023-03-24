/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.curl

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.internal.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

internal class CurlClientEngine(
    override val config: CurlClientEngineConfig
) : HttpClientEngineBase("ktor-curl") {
    override val dispatcher = Dispatchers.Unconfined

    override val supportedCapabilities = setOf(HttpTimeout)

    private val curlProcessor = CurlProcessor(coroutineContext)

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()

        val requestTime = GMTDate()

        val curlRequest = data.toCurlRequest(config)
        val responseData = curlProcessor.executeRequest(curlRequest)

        return with(responseData) {
            val headerBytes = ByteReadChannel(headersBytes).apply {
                readUTF8Line()
            }
            val rawHeaders = parseHeaders(headerBytes)

            val status = HttpStatusCode.fromValue(status)

            val headers = HeadersImpl(rawHeaders.toMap())
            rawHeaders.release()

            HttpResponseData(
                status,
                requestTime,
                headers,
                version.fromCurl(),
                bodyChannel,
                callContext
            )
        }
    }

    override fun close() {
        super.close()
        curlProcessor.close()
    }
}

@Suppress("KDocMissingDocumentation")
@Deprecated("This exception will be removed in a future release in favor of a better error handling.")
public class CurlIllegalStateException(cause: String) : IllegalStateException(cause)

@Suppress("KDocMissingDocumentation")
@Deprecated("This exception will be removed in a future release in favor of a better error handling.")
public class CurlRuntimeException(cause: String) : RuntimeException(cause)
