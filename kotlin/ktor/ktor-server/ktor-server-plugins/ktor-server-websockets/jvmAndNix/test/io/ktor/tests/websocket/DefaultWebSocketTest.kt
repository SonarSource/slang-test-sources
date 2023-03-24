/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.tests.websocket

import io.ktor.server.testing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWebSocketTest : BaseTest() {

    private lateinit var parent: CompletableJob
    private lateinit var client2server: ByteChannel
    private lateinit var server2client: ByteChannel

    private lateinit var server: DefaultWebSocketSession

    private lateinit var client: WebSocketSession

    @OptIn(InternalAPI::class)
    @BeforeTest
    fun prepare() {
        parent = Job()
        client2server = ByteChannel()
        server2client = ByteChannel()

        server = DefaultWebSocketSession(
            RawWebSocket(client2server, server2client, coroutineContext = parent),
            -1L,
            1000L
        )
        server.start()

        client = RawWebSocket(server2client, client2server, coroutineContext = parent)
    }

    @AfterTest
    fun cleanup() {
        server.cancel()
        client.cancel()
        client2server.cancel()
        server2client.cancel()
        parent.cancel()
    }

    @Test
    fun closeByClient(): Unit = runTest {
        val reason = CloseReason(CloseReason.Codes.NORMAL, "test1")

        client.close(reason)
        assertEquals(reason, server.closeReason.await())

        // server for sure received a close frame so it should reply with a duplicate close frame
        // so we should be able to receive it at client side

        val closed = client.incoming.receive() as Frame.Close
        assertEquals(reason, closed.readReason())

        ensureCompletion()
    }

    @Test
    fun pingPong(): Unit = runTest {
        val pingsMessages = (1..5).map { "ping $it" }

        pingsMessages.forEach {
            client.send(Frame.Ping(it.encodeToByteArray()))
        }
        pingsMessages.forEach {
            assertEquals(it, String((client.incoming.receive() as Frame.Pong).readBytes(), charset = Charsets.UTF_8))
        }

        client.close()
        assertTrue(client.incoming.receive() is Frame.Close)

        ensureCompletion()
    }

    @Test
    @OptIn(InternalAPI::class)
    fun testPingPongTimeout(): Unit = runTest {
        cleanup()

        parent = Job()
        client2server = ByteChannel()
        server2client = ByteChannel()

        server = DefaultWebSocketSession(
            RawWebSocket(client2server, server2client, coroutineContext = parent),
            500L,
            500L
        )
        server.start()

        client = RawWebSocket(server2client, client2server, coroutineContext = parent)
        assertTrue(client.incoming.receive() is Frame.Ping)
        delay(1000)
        assertTrue(client.incoming.receive() is Frame.Close)

        assertTrue("server incoming should be closed") { server.incoming.isClosedForReceive }
        assertTrue("server outgoing should be closed") { server.outgoing.isClosedForSend }
        assertTrue("server should be closed") { server.closeReason.isCompleted }
        client.close()
    }

    @Test
    fun testCancellation(): Unit = runTest {
        server.cancel()

        client.incoming.receiveCatching().getOrNull()
        client.close()

        ensureCompletion()
    }

    private suspend fun ensureCompletion() {
        parent.complete()
        parent.join()

        assertTrue("client -> server channel should be closed") { client2server.isClosedForRead }
        assertTrue("client -> server channel should be closed") { client2server.isClosedForWrite }

        assertTrue("server -> client channel should be closed") { server2client.isClosedForRead }
        assertTrue("server -> client channel should be closed") { server2client.isClosedForWrite }

        try {
            server.incoming.consumeEach {
                assertTrue("It should be no control frames") { !it.frameType.controlFrame }
            }
        } catch (_: CancellationException) {
        }

        try {
            client.incoming.consumeEach {}
        } catch (_: CancellationException) {
        }

        assertTrue("client incoming should be closed") { client.incoming.isClosedForReceive }
        assertTrue("server incoming should be closed") { server.incoming.isClosedForReceive }

        assertTrue("client outgoing should be closed") { client.outgoing.isClosedForSend }
        assertTrue("server outgoing should be closed") { server.outgoing.isClosedForSend }

        assertTrue("server closeReason should be completed") { server.closeReason.isCompleted }
    }
}
