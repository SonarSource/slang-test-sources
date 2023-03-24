/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.engine

import io.ktor.server.engine.internal.*
import io.ktor.util.*
import kotlinx.coroutines.*

/**
 * Stop server on job cancellation. The returned deferred need to be completed or cancelled.
 */
@OptIn(InternalAPI::class)
public fun ApplicationEngine.stopServerOnCancellation(
    gracePeriodMillis: Long = 50,
    timeoutMillis: Long = 5000
): CompletableJob =
    environment.parentCoroutineContext[Job]?.launchOnCancellation {
        stop(gracePeriodMillis, timeoutMillis)
    } ?: Job()

/**
 * Launch a coroutine with [block] body when the parent job is cancelled or a returned deferred is cancelled.
 * It is important to complete or cancel returned deferred
 * otherwise the parent job will be unable to complete successfully.
 */
@OptIn(DelicateCoroutinesApi::class)
@InternalAPI
public fun Job.launchOnCancellation(block: suspend () -> Unit): CompletableJob {
    val deferred: CompletableJob = Job(parent = this)

    @OptIn(ExperimentalCoroutinesApi::class)
    GlobalScope.launch(this + Dispatchers.IOBridge) {
        var cancelled = false
        try {
            deferred.join()
        } catch (_: Throwable) {
            cancelled = true
        }

        if (cancelled || deferred.isCancelled) {
            block()
        }
    }

    return deferred
}
