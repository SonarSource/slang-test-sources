/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.utils.io.core.*

class PosixReadPacketWithExceptionByteChannelCloseTest : ByteChannelCloseTest(
    EOFException::class,
    { close() },
    { readPacket(Int.MAX_VALUE) }
)

class PosixReadFullyWithExceptionByteChannelCloseTest : ByteChannelCloseTest(
    EOFException::class,
    { close() },
    { readFully(ByteArray(10)) }
)
