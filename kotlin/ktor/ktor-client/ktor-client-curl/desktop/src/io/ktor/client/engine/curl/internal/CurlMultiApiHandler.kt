/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.engine.curl.internal

import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.locks.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import libcurl.*

private class RequestHolder(
    val responseCompletable: CompletableDeferred<CurlSuccess>,
    val requestWrapper: StableRef<CurlRequestBodyData>,
    val responseWrapper: StableRef<CurlResponseBodyData>,
) {
    fun dispose() {
        requestWrapper.dispose()
        responseWrapper.dispose()
    }
}

internal class CurlMultiApiHandler : Closeable {
    private val activeHandles = mutableMapOf<EasyHandle, RequestHolder>()

    private val cancelledHandles = mutableSetOf<Pair<EasyHandle, Throwable>>()

    private val multiHandle: MultiHandle = curl_multi_init()
        ?: @Suppress("DEPRECATION") throw CurlRuntimeException("Could not initialize curl multi handle")

    private val easyHandlesToUnpauseLock = SynchronizedObject()
    private val easyHandlesToUnpause = mutableListOf<EasyHandle>()

    override fun close() {
        for ((handle, holder) in activeHandles) {
            curl_multi_remove_handle(multiHandle, handle).verify()
            curl_easy_cleanup(handle)
            holder.dispose()
        }

        activeHandles.clear()
        curl_multi_cleanup(multiHandle).verify()
    }

    fun scheduleRequest(request: CurlRequestData, deferred: CompletableDeferred<CurlSuccess>): EasyHandle {
        val easyHandle = curl_easy_init()
            ?: throw @Suppress("DEPRECATION") CurlIllegalStateException("Could not initialize an easy handle")

        val bodyStartedReceiving = CompletableDeferred<Unit>()
        val responseData = CurlResponseBuilder(request)
        val responseDataRef = responseData.asStablePointer()

        val responseWrapper = CurlResponseBodyData(
            body = responseData.bodyChannel,
            callContext = request.executionContext,
            bodyStartedReceiving = bodyStartedReceiving,
            onUnpause = {
                synchronized(easyHandlesToUnpauseLock) {
                    easyHandlesToUnpause.add(easyHandle)
                }
                curl_multi_wakeup(multiHandle)
            }
        ).asStablePointer()

        bodyStartedReceiving.invokeOnCompletion {
            val result = collectSuccessResponse(easyHandle) ?: return@invokeOnCompletion
            activeHandles[easyHandle]!!.responseCompletable.complete(result)
        }

        setupMethod(easyHandle, request.method, request.contentLength)
        val requestWrapper = setupUploadContent(easyHandle, request)
        val requestHolder = RequestHolder(
            deferred,
            requestWrapper.asStableRef(),
            responseWrapper.asStableRef()
        )

        activeHandles[easyHandle] = requestHolder

        easyHandle.apply {
            option(CURLOPT_URL, request.url)
            option(CURLOPT_HTTPHEADER, request.headers)
            option(CURLOPT_HEADERFUNCTION, staticCFunction(::onHeadersReceived))
            option(CURLOPT_HEADERDATA, responseDataRef)
            option(CURLOPT_WRITEFUNCTION, staticCFunction(::onBodyChunkReceived))
            option(CURLOPT_WRITEDATA, responseWrapper)
            option(CURLOPT_PRIVATE, responseDataRef)
            option(CURLOPT_ACCEPT_ENCODING, "")
            request.connectTimeout?.let {
                if (it != HttpTimeout.INFINITE_TIMEOUT_MS) {
                    option(CURLOPT_CONNECTTIMEOUT_MS, request.connectTimeout)
                } else {
                    option(CURLOPT_CONNECTTIMEOUT_MS, Long.MAX_VALUE)
                }
            }

            request.proxy?.let { proxy ->
                option(CURLOPT_PROXY, proxy.toString())
                option(CURLOPT_SUPPRESS_CONNECT_HEADERS, 1L)
                if (request.forceProxyTunneling) {
                    option(CURLOPT_HTTPPROXYTUNNEL, 1L)
                }
            }

            if (!request.sslVerify) {
                option(CURLOPT_SSL_VERIFYPEER, 0L)
                option(CURLOPT_SSL_VERIFYHOST, 0L)
            }
            request.caPath?.let { option(CURLOPT_CAPATH, it) }
            request.caInfo?.let { option(CURLOPT_CAINFO, it) }
        }

        curl_multi_add_handle(multiHandle, easyHandle).verify()

        return easyHandle
    }

    internal fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) {
        cancelledHandles += Pair(easyHandle, cause)
        curl_multi_remove_handle(multiHandle, easyHandle).verify()
    }

    internal fun perform() {
        if (activeHandles.isEmpty()) return

        memScoped {
            val transfersRunning = alloc<IntVar>()
            do {
                synchronized(easyHandlesToUnpauseLock) {
                    var handle = easyHandlesToUnpause.removeFirstOrNull()
                    while (handle != null) {
                        curl_easy_pause(handle, CURLPAUSE_CONT)
                        handle = easyHandlesToUnpause.removeFirstOrNull()
                    }
                }
                curl_multi_perform(multiHandle, transfersRunning.ptr).verify()
                if (transfersRunning.value != 0) {
                    curl_multi_poll(multiHandle, null, 0.toUInt(), 10000, null).verify()
                }
                if (transfersRunning.value < activeHandles.size) {
                    handleCompleted()
                }
            } while (transfersRunning.value != 0)
        }
    }

    internal fun hasHandlers(): Boolean = activeHandles.isNotEmpty()

    private fun setupMethod(
        easyHandle: EasyHandle,
        method: String,
        size: Long
    ) {
        easyHandle.apply {
            when (method) {
                "GET" -> option(CURLOPT_HTTPGET, 1L)
                "PUT" -> option(CURLOPT_PUT, 1L)
                "POST" -> {
                    option(CURLOPT_POST, 1L)
                    option(CURLOPT_POSTFIELDSIZE, size)
                }

                "HEAD" -> option(CURLOPT_NOBODY, 1L)
                else -> {
                    if (size > 0) option(CURLOPT_POST, 1L)
                    option(CURLOPT_CUSTOMREQUEST, method)
                }
            }
        }
    }

    private fun setupUploadContent(easyHandle: EasyHandle, request: CurlRequestData): COpaquePointer {
        val requestPointer = CurlRequestBodyData(
            body = request.content,
            callContext = request.executionContext,
            onUnpause = {
                synchronized(easyHandlesToUnpauseLock) {
                    easyHandlesToUnpause.add(easyHandle)
                }
                curl_multi_wakeup(multiHandle)
            }
        ).asStablePointer()

        easyHandle.apply {
            option(CURLOPT_READDATA, requestPointer)
            option(CURLOPT_READFUNCTION, staticCFunction(::onBodyChunkRequested))
            option(CURLOPT_INFILESIZE_LARGE, request.contentLength)
        }
        return requestPointer
    }

    private fun handleCompleted() {
        for (cancellation in cancelledHandles) {
            val cancelled = processCancelledEasyHandle(cancellation.first, cancellation.second)
            val handler = activeHandles.remove(cancellation.first)!!
            handler.responseCompletable.completeExceptionally(cancelled.cause)
            handler.dispose()
        }
        cancelledHandles.clear()

        memScoped {
            do {
                val messagesLeft = alloc<IntVar>()
                val messagePtr = curl_multi_info_read(multiHandle, messagesLeft.ptr)
                val message = messagePtr?.pointed ?: continue

                val easyHandle = message.easy_handle
                    ?: @Suppress("DEPRECATION")
                    throw CurlIllegalStateException("Got a null easy handle from the message")

                try {
                    val result = processCompletedEasyHandle(message.msg, easyHandle, message.data.result)
                    val deferred = activeHandles[easyHandle]!!.responseCompletable
                    if (deferred.isCompleted) {
                        // already completed with partial response
                        continue
                    }
                    when (result) {
                        is CurlSuccess -> deferred.complete(result)
                        is CurlFail -> deferred.completeExceptionally(result.cause)
                    }
                } finally {
                    activeHandles.remove(easyHandle)!!.dispose()
                }
            } while (messagesLeft.value != 0)
        }
    }

    private fun processCancelledEasyHandle(easyHandle: EasyHandle, cause: Throwable): CurlFail = memScoped {
        try {
            val responseDataRef = alloc<COpaquePointerVar>()
            easyHandle.apply { getInfo(CURLINFO_PRIVATE, responseDataRef.ptr) }
            val responseBuilder = responseDataRef.value!!.fromCPointer<CurlResponseBuilder>()
            try {
                return CurlFail(cause)
            } finally {
                responseBuilder.bodyChannel.close(cause)
                responseBuilder.headersBytes.release()
            }
        } finally {
            curl_multi_remove_handle(multiHandle, easyHandle).verify()
            curl_easy_cleanup(easyHandle)
        }
    }

    private fun processCompletedEasyHandle(
        message: CURLMSG?,
        easyHandle: EasyHandle,
        result: CURLcode
    ): CurlResponseData = memScoped {
        try {
            val responseDataRef = alloc<COpaquePointerVar>()
            val httpStatusCode = alloc<LongVar>()

            easyHandle.apply {
                getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
                getInfo(CURLINFO_PRIVATE, responseDataRef.ptr)
            }

            val responseBuilder = responseDataRef.value!!.fromCPointer<CurlResponseBuilder>()
            try {
                collectFailedResponse(message, responseBuilder.request, result, httpStatusCode.value)
                    ?: collectSuccessResponse(easyHandle)!!
            } finally {
                responseBuilder.bodyChannel.close(null)
                responseBuilder.headersBytes.release()
            }
        } finally {
            curl_multi_remove_handle(multiHandle, easyHandle).verify()
            curl_easy_cleanup(easyHandle)
        }
    }

    private fun collectFailedResponse(
        message: CURLMSG?,
        request: CurlRequestData,
        result: CURLcode,
        httpStatusCode: Long
    ): CurlFail? {
        curl_slist_free_all(request.headers)

        if (message != CURLMSG.CURLMSG_DONE) {
            return CurlFail(
                @Suppress("DEPRECATION")
                CurlIllegalStateException("Request $request failed: $message")
            )
        }

        if (httpStatusCode != 0L) {
            return null
        }

        if (result == CURLE_OPERATION_TIMEDOUT) {
            return CurlFail(ConnectTimeoutException(request.url, request.connectTimeout))
        }

        val errorMessage = curl_easy_strerror(result)?.toKStringFromUtf8()

        if (result == CURLE_PEER_FAILED_VERIFICATION) {
            return CurlFail(
                @Suppress("DEPRECATION")
                CurlIllegalStateException(
                    "TLS verification failed for request: $request. Reason: $errorMessage"
                )
            )
        }

        return CurlFail(
            @Suppress("DEPRECATION")
            CurlIllegalStateException("Connection failed for request: $request. Reason: $errorMessage")
        )
    }

    private fun collectSuccessResponse(easyHandle: EasyHandle): CurlSuccess? = memScoped {
        val responseDataRef = alloc<COpaquePointerVar>()
        val httpProtocolVersion = alloc<LongVar>()
        val httpStatusCode = alloc<LongVar>()

        easyHandle.apply {
            getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
            getInfo(CURLINFO_PRIVATE, responseDataRef.ptr)
        }

        if (httpStatusCode.value == 0L) {
            // if error happened, it will be handled in collectCompleted
            return@memScoped null
        }

        val responseBuilder = responseDataRef.value!!.fromCPointer<CurlResponseBuilder>()
        with(responseBuilder) {
            val headers = headersBytes.build().readBytes()

            CurlSuccess(
                httpStatusCode.value.toInt(),
                httpProtocolVersion.value.toUInt(),
                headers,
                bodyChannel
            )
        }
    }

    fun wakeup() {
        curl_multi_wakeup(multiHandle)
    }
}
