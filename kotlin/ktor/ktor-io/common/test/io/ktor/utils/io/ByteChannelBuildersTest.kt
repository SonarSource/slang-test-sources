/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.utils.io

import io.ktor.test.dispatcher.*
import kotlinx.coroutines.*
import kotlin.test.*

class ByteChannelBuildersTest {
    @Test
    fun testWriterCancelledByChannel() = testSuspend {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)

        val task = scope.writer {
            val data = ByteArray(8 * 1024)
            while (true) {
                channel.writeFully(data)
            }
        }

        job.complete()
        task.channel.cancel()
        job.join()
        assertTrue(task.isCancelled)
        assertTrue(job.isCompleted)
    }
}
