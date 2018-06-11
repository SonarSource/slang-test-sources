package io.ktor.client.features.json

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.util.*

/**
 * [HttpClient] feature that serializes/de-serializes as JSON custom objects
 * to request and from response bodies using a [serializer].
 *
 * The default [serializer] is [GsonSerializer].
 *
 * Note: It will de-serialize the body response if the specified type is a public accessible class
 *       and the Content-Type is `application/json`
 */
class JsonFeature(val serializer: JsonSerializer) {
    class Config {
        /**
         * Serialized that will be used for serializing requests bodies,
         * and de-serializing response bodies when Content-Type matches `application/json`.
         */
        var serializer: JsonSerializer = GsonSerializer()
    }

    companion object Feature : HttpClientFeature<Config, JsonFeature> {
        override val key: AttributeKey<JsonFeature> = AttributeKey("Json")

        override suspend fun prepare(block: Config.() -> Unit): JsonFeature =
            Config().apply(block).let { JsonFeature(it.serializer) }

        override fun install(feature: JsonFeature, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Transform) { payload ->

                context.accept(ContentType.Application.Json)
                if (context.contentType()?.match(ContentType.Application.Json) != true) {
                    return@intercept
                }

                context.headers.remove(HttpHeaders.ContentType)
                proceedWith(feature.serializer.write(payload))
            }

            scope.responsePipeline.intercept(HttpResponsePipeline.Transform) { (expectedType, response) ->
                if (response !is HttpResponse
                    || context.response.contentType()?.match(ContentType.Application.Json) != true
                ) return@intercept

                proceedWith(HttpResponseContainer(expectedType, feature.serializer.read(expectedType, response)))
            }
        }
    }
}