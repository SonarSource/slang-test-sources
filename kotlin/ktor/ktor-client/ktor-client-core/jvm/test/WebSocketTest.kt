/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.*
import kotlin.test.*

class WebSocketTest {

    @Test
    fun testAsDefault() {
        val plugin = WebSockets(42, 16)
        val session = object : WebSocketSession {
            override var masking: Boolean = false
            override var maxFrameSize: Long = 0
            override val incoming: ReceiveChannel<Frame> = Channel()
            override val outgoing: SendChannel<Frame> = Channel()

            override val extensions: List<WebSocketExtension<*>>
                get() = TODO("Not yet implemented")

            override suspend fun send(frame: Frame) {
                TODO("Not yet implemented")
            }

            override suspend fun flush() {
                TODO("Not yet implemented")
            }

            @Deprecated(
                "Use cancel() instead.",
                ReplaceWith("cancel()", "kotlinx.coroutines.cancel"),
                level = DeprecationLevel.ERROR
            )
            override fun terminate() {
                TODO("Not yet implemented")
            }

            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        }

        val defaultSession = plugin.convertSessionToDefault(session)
        assertEquals(16, defaultSession.maxFrameSize)
    }
}
