/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.request

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.application.internal.*
import io.ktor.server.plugins.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

/**
 * A pipeline for processing incoming content.
 * When executed, this pipeline starts with an instance of [ByteReadChannel].
 */
public open class ApplicationReceivePipeline(
    override val developmentMode: Boolean = false
) : Pipeline<Any, ApplicationCall>(Before, Transform, After) {
    /**
     * Pipeline phases.
     */
    @Suppress("PublicApiImplicitType")
    public companion object Phases {
        /**
         * Executes before any transformations are made.
         */
        public val Before: PipelinePhase = PipelinePhase("Before")

        /**
         * Executes transformations.
         */
        public val Transform: PipelinePhase = PipelinePhase("Transform")

        /**
         * Executes after all transformations.
         */
        public val After: PipelinePhase = PipelinePhase("After")
    }
}

/**
 * Receives content for this request.
 * @return instance of [T] received from this call, or `null` if content cannot be transformed to the requested type.
 */
@Deprecated(
    "receiveOrNull is ambiguous with receiveNullable and going to be removed in 3.0.0. " +
        "Please consider replacing it with runCatching with receive or receiveNullable",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith("kotlin.runCatching { this.receiveNullable<T>() }.getOrNull()")
)
@Suppress("DEPRECATION")
public suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = receiveOrNull(typeInfo<T>())

/**
 * Receives content for this request.
 * @return instance of [T] received from this call.
 * @throws ContentTransformationException when content cannot be transformed to the requested type.
 */
public suspend inline fun <reified T : Any> ApplicationCall.receive(): T = receiveNullable(typeInfo<T>())
    ?: throw CannotTransformContentToTypeException(typeInfo<T>().kotlinType!!)

/**
 * Receives content for this request.
 * @return instance of [T] received from this call.
 * @throws ContentTransformationException when content cannot be transformed to the requested type.
 */
public suspend inline fun <reified T> ApplicationCall.receiveNullable(): T? = receiveNullable(typeInfo<T>())

/**
 * Receives content for this request.
 * @param type instance of `KClass` specifying type to be received.
 * @return instance of [T] received from this call.
 * @throws ContentTransformationException when content cannot be transformed to the requested type.
 */
public suspend fun <T : Any> ApplicationCall.receive(type: KClass<T>): T {
    val kotlinType = starProjectedTypeBridge(type)
    return receiveNullable(TypeInfo(type, kotlinType.platformType, kotlinType))!!
}

/**
 * Receives content for this request.
 * @param typeInfo instance specifying type to be received.
 * @return instance of [T] received from this call.
 * @throws ContentTransformationException when content cannot be transformed to the requested type.
 */
public suspend fun <T> ApplicationCall.receiveNullable(typeInfo: TypeInfo): T? {
    val token = attributes.getOrNull(DoubleReceivePreventionTokenKey)
    if (token == null) {
        attributes.put(DoubleReceivePreventionTokenKey, DoubleReceivePreventionToken)
    }

    receiveType = typeInfo
    val incomingContent = token ?: request.receiveChannel()
    val transformed = request.pipeline.execute(this, incomingContent)
    when {
        transformed == NullBody -> return null
        transformed === DoubleReceivePreventionToken -> throw RequestAlreadyConsumedException()
        !typeInfo.type.isInstance(transformed) -> throw CannotTransformContentToTypeException(typeInfo.kotlinType!!)
    }

    @Suppress("UNCHECKED_CAST")
    return transformed as T
}

/**
 * Receives content for this request.
 * @param typeInfo instance specifying type to be received.
 * @return instance of [T] received from this call.
 * @throws ContentTransformationException when content cannot be transformed to the requested type.
 * @throws NullPointerException when content is `null`.
 */
public suspend fun <T> ApplicationCall.receive(typeInfo: TypeInfo): T = receiveNullable(typeInfo)!!

/**
 * Receives content for this request.
 * @param [typeInfo] type to be received.
 * @return instance of [T] received from this call, or `null` if content cannot be transformed to the requested type.
 */
@Deprecated(
    "receiveOrNull is ambiguous with receiveNullable and going to be removed in 3.0.0. " +
        "Please consider replacing it with runCatching with receive or receiveNullable",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith("kotlin.runCatching { this.receiveNullable<T>() }.getOrNull()")
)
public suspend fun <T : Any> ApplicationCall.receiveOrNull(typeInfo: TypeInfo): T? {
    return try {
        receiveNullable(typeInfo)
    } catch (cause: ContentTransformationException) {
        application.log.debug("Conversion failed, null returned", cause)
        null
    }
}

/**
 * Receives content for this request.
 * @param type instance of `KClass` specifying type to be received.
 * @return instance of [T] received from this call, or `null` if content cannot be transformed to the requested type..
 */
@Deprecated(
    "receiveOrNull is ambiguous with receiveNullable and going to be removed in 3.0.0. " +
        "Please consider replacing it with runCatching with receive or receiveNullable",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith("kotlin.runCatching { this.receiveNullable<T>() }.getOrNull()")
)
public suspend fun <T : Any> ApplicationCall.receiveOrNull(type: KClass<T>): T? = try {
    receive(type)
} catch (cause: ContentTransformationException) {
    application.log.debug("Conversion failed, null returned", cause)
    null
}

/**
 * Receives incoming content for this call as [String].
 * @return text received from this call.
 * @throws BadRequestException when Content-Type header is invalid.
 */
public suspend inline fun ApplicationCall.receiveText(): String {
    val charset = try {
        request.contentCharset() ?: Charsets.UTF_8
    } catch (cause: BadContentTypeFormatException) {
        throw BadRequestException("Illegal Content-Type format: ${request.headers[HttpHeaders.ContentType]}", cause)
    }
    return receiveChannel().readRemaining().readText(charset)
}

/**
 * Receives channel content for this call.
 * @return instance of [ByteReadChannel] to read incoming bytes for this call.
 * @throws ContentTransformationException when content cannot be transformed to the [ByteReadChannel].
 */
public suspend inline fun ApplicationCall.receiveChannel(): ByteReadChannel = receive()

/**
 * Receives multipart data for this call.
 * @return instance of [MultiPartData].
 * @throws ContentTransformationException when content cannot be transformed to the [MultiPartData].
 */
public suspend inline fun ApplicationCall.receiveMultipart(): MultiPartData = receive()

/**
 * Receives form parameters for this call.
 * @return instance of [Parameters].
 * @throws ContentTransformationException when content cannot be transformed to the [Parameters].
 */
public suspend inline fun ApplicationCall.receiveParameters(): Parameters = receive()

/**
 * Thrown when content cannot be transformed to the desired type.
 */
public typealias ContentTransformationException = io.ktor.server.plugins.ContentTransformationException

/**
 * This object is attached to an [ApplicationCall] with [DoubleReceivePreventionTokenKey] when
 * the [receive] function is invoked. It is used to detect double receive invocation
 * that causes [RequestAlreadyConsumedException] to be thrown unless the [DoubleReceive] plugin installed.
 */
private object DoubleReceivePreventionToken

private val DoubleReceivePreventionTokenKey = AttributeKey<DoubleReceivePreventionToken>("DoubleReceivePreventionToken")

/**
 * Thrown when a request body has already been received.
 * Usually it is caused by double [ApplicationCall.receive] invocation.
 */
public class RequestAlreadyConsumedException : IllegalStateException(
    "Request body has already been consumed (received)."
)
