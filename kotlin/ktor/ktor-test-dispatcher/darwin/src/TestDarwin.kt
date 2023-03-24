@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.ktor.test.dispatcher

import kotlinx.coroutines.*
import platform.Foundation.*
import platform.posix.*
import kotlin.coroutines.*

/**
 * Amount of time any task is processed and can't be rescheduled.
 */
private const val TIME_QUANTUM = 0.01

/**
 * Test runner for native suspend tests.
 */
public actual fun testSuspend(
    context: CoroutineContext,
    timeoutMillis: Long,
    block: suspend CoroutineScope.() -> Unit
) {
    executeInWorker(timeoutMillis) {
        runBlocking {
            val loop = ThreadLocalEventLoop.currentOrNull()!!

            val task = launch { block() }
            while (!task.isCompleted) {
                val date = NSDate().addTimeInterval(TIME_QUANTUM) as NSDate
                NSRunLoop.mainRunLoop.runUntilDate(date)

                loop.processNextEvent()
            }
        }
    }
}
