/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.plugins

import io.ktor.client.*
import io.ktor.client.content.*
import io.ktor.client.plugins.observer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.native.concurrent.*

private val UploadProgressListenerAttributeKey =
    AttributeKey<ProgressListener>("UploadProgressListenerAttributeKey")

private val DownloadProgressListenerAttributeKey =
    AttributeKey<ProgressListener>("DownloadProgressListenerAttributeKey")

/**
 * Plugin that provides observable progress for uploads and downloads
 */
public class BodyProgress internal constructor() {

    private fun handle(scope: HttpClient) {
        val observableContentPhase = PipelinePhase("ObservableContent")
        scope.requestPipeline.insertPhaseAfter(reference = HttpRequestPipeline.Render, phase = observableContentPhase)
        scope.requestPipeline.intercept(observableContentPhase) { content ->
            val listener = context.attributes
                .getOrNull(UploadProgressListenerAttributeKey) ?: return@intercept

            val observableContent = ObservableContent(content as OutgoingContent, context.executionContext, listener)
            proceedWith(observableContent)
        }

        scope.receivePipeline.intercept(HttpReceivePipeline.After) { response ->
            val listener = response.call.request.attributes
                .getOrNull(DownloadProgressListenerAttributeKey) ?: return@intercept
            val observableResponse = response.withObservableDownload(listener)
            proceedWith(observableResponse)
        }
    }

    public companion object Plugin : HttpClientPlugin<Unit, BodyProgress> {
        override val key: AttributeKey<BodyProgress> = AttributeKey("BodyProgress")

        override fun prepare(block: Unit.() -> Unit): BodyProgress {
            return BodyProgress()
        }

        override fun install(plugin: BodyProgress, scope: HttpClient) {
            plugin.handle(scope)
        }
    }
}

@OptIn(InternalAPI::class)
internal fun HttpResponse.withObservableDownload(listener: ProgressListener): HttpResponse {
    val observableByteChannel = content.observable(coroutineContext, contentLength(), listener)
    return call.wrapWithContent(observableByteChannel).response
}

/**
 * Registers listener to observe download progress.
 */
public fun HttpRequestBuilder.onDownload(listener: ProgressListener?) {
    if (listener == null) {
        attributes.remove(DownloadProgressListenerAttributeKey)
    } else {
        attributes.put(DownloadProgressListenerAttributeKey, listener)
    }
}

/**
 * Registers listener to observe upload progress.
 */
public fun HttpRequestBuilder.onUpload(listener: ProgressListener?) {
    if (listener == null) {
        attributes.remove(UploadProgressListenerAttributeKey)
    } else {
        attributes.put(UploadProgressListenerAttributeKey, listener)
    }
}
