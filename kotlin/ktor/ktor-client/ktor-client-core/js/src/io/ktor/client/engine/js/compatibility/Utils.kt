/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.js.compatibility

import io.ktor.client.engine.js.browser.*
import io.ktor.client.engine.js.node.*
import io.ktor.client.fetch.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.js.Promise

internal suspend fun commonFetch(
    input: String,
    init: RequestInit
): org.w3c.fetch.Response = suspendCancellableCoroutine { continuation ->
    val controller = AbortController()
    init.signal = controller.signal

    continuation.invokeOnCancellation {
        controller.abort()
    }

    val promise: Promise<org.w3c.fetch.Response> = when (PlatformUtils.platform) {
        Platform.Browser -> fetch(input, init)
        else -> jsRequireNodeFetch()(input, init)
    }

    promise.then(
        onFulfilled = {
            continuation.resumeWith(Result.success(it))
        },
        onRejected = {
            continuation.resumeWith(Result.failure(Error("Fail to fetch", it)))
        }
    )
}

internal fun AbortController(): AbortController {
    return when (PlatformUtils.platform) {
        Platform.Browser -> js("new AbortController()")
        else -> {
            @Suppress("UNUSED_VARIABLE")
            val controller = js("eval('require')('abort-controller')")
            js("new controller()")
        }
    }
}

internal fun CoroutineScope.readBody(
    response: org.w3c.fetch.Response
): ByteReadChannel = when (PlatformUtils.platform) {
    Platform.Node -> readBodyNode(response)
    else -> readBodyBrowser(response)
}

private fun jsRequireNodeFetch(): dynamic = try {
    js("eval('require')('node-fetch')")
} catch (cause: dynamic) {
    throw Error("Error loading module 'node-fetch': $cause")
}
