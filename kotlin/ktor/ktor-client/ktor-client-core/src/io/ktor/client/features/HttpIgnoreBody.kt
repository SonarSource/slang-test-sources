package io.ktor.client.features

import io.ktor.client.*
import io.ktor.client.response.*
import io.ktor.util.*
import io.ktor.client.call.*

/**
 * [HttpClient] feature that closes the [HttpClientCall.response] early for optimizing resources.
 *
 * This feature doesn't have anything to configure.
 */
class HttpIgnoreBody {
    companion object Feature : HttpClientFeature<Unit, HttpIgnoreBody> {
        override val key: AttributeKey<HttpIgnoreBody> = AttributeKey("HttpIgnoreBody")

        override suspend fun prepare(block: Unit.() -> Unit): HttpIgnoreBody = HttpIgnoreBody()

        override fun install(feature: HttpIgnoreBody, scope: HttpClient) {
            scope.responsePipeline.intercept(HttpResponsePipeline.Transform) { data ->
                if (data.expectedType != Unit::class) return@intercept
                context.response.close()
                proceedWith(data.copy(response = Unit))
            }
        }
    }
}