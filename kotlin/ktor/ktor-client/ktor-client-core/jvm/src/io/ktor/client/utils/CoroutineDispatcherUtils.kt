/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.utils

import io.ktor.util.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.scheduling.*
import java.io.*
import kotlin.coroutines.*

/**
 * Creates [CoroutineDispatcher] based on thread pool of [threadCount] threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@InternalAPI
public actual fun Dispatchers.clientDispatcher(
    threadCount: Int,
    dispatcherName: String
): CoroutineDispatcher = IO.limitedParallelism(threadCount)
