/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.ktor.utils.io

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*

/**
 * Channel for asynchronous reading and writing of sequences of bytes.
 * This is a buffered **single-reader single-writer channel**.
 *
 * Read operations can be invoked concurrently with write operations, but multiple reads or multiple writes
 * cannot be invoked concurrently with themselves. Exceptions are [close] and [flush] which can be invoked
 * concurrently with any other operations and between themselves at any time.
 */
public interface ByteChannel : ByteReadChannel, ByteWriteChannel {
    public fun attachJob(job: Job)
}

/**
 * Creates buffered channel for asynchronous reading and writing of sequences of bytes.
 */
public expect fun ByteChannel(autoFlush: Boolean = false): ByteChannel

/**
 * Creates channel for reading from the specified byte array. Please note that it could use [content] directly
 * or copy its bytes depending on the platform.
 */
public fun ByteReadChannel(content: ByteArray): ByteReadChannel =
    ByteReadChannel(content, 0, content.size)

/**
 * Creates channel for reading from the specified byte array. Please note that it could use [content] directly
 * or copy its bytes depending on the platform.
 */
public fun ByteReadChannel(content: ByteArray, offset: Int): ByteReadChannel =
    ByteReadChannel(content, offset, content.size)

/**
 * Creates channel for reading from the specified byte array. Please note that it could use [content] directly
 * or copy its bytes depending on the platform
 */
public expect fun ByteReadChannel(content: ByteArray, offset: Int, length: Int): ByteReadChannel

public fun ByteReadChannel(text: String, charset: Charset = Charsets.UTF_8): ByteReadChannel =
    ByteReadChannel(text.toByteArray(charset)) // TODO optimize to encode parts on demand
