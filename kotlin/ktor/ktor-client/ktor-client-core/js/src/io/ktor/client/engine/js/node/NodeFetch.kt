/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.js.node

import io.ktor.client.engine.js.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.khronos.webgl.*
import org.w3c.fetch.*

internal fun CoroutineScope.readBodyNode(response: Response): ByteReadChannel = writer {
    val body: dynamic = response.body ?: error("Fail to get body")

    val responseData = Channel<ByteArray>(1)

    body.on("data") { chunk: ArrayBuffer ->
        responseData.trySend(Uint8Array(chunk).asByteArray()).isSuccess
        body.pause()
    }

    body.on("error") { error ->
        val cause = JsError(error)
        responseData.close(cause)
        channel.close(cause)
    }

    body.on("end") {
        responseData.close()
    }

    try {
        for (chunk in responseData) {
            channel.writeFully(chunk)
            body.resume()
        }
    } catch (cause: Throwable) {
        body.destroy(cause)
        throw cause
    }

    Unit
}.channel
