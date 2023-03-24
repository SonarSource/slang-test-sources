/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io.internal

import io.ktor.utils.io.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*

/**
 * Exclusive slot for waiting.
 * Only one waiter allowed.
 *
 * TODO: replace [Job] -> [Continuation] when all coroutines problems are fixed.
 */
internal class AwaitingSlot {
    private val suspension: AtomicRef<CompletableJob?> = atomic(null)

    /**
     * Wait for other [sleep] or resume.
     */
    suspend fun sleep(sleepCondition: () -> Boolean) {
        if (trySuspend(sleepCondition)) {
            return
        }

        resume()
    }

    /**
     * Resume waiter.
     */
    fun resume() {
        suspension.getAndSet(null)?.complete()
    }

    /**
     * Cancel waiter.
     */
    fun cancel(cause: Throwable?) {
        val continuation = suspension.getAndSet(null) ?: return

        if (cause != null) {
            continuation.completeExceptionally(cause)
        } else {
            continuation.complete()
        }
    }

    private suspend fun trySuspend(sleepCondition: () -> Boolean): Boolean {
        var suspended = false

        val job = Job()
        if (suspension.compareAndSet(null, job) && sleepCondition()) {
            suspended = true
            job.join()
        }

        return suspended
    }
}
