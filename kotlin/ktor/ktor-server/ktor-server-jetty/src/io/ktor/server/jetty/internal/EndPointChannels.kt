package io.ktor.server.jetty.internal

import io.ktor.cio.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.pool.*
import org.eclipse.jetty.io.*
import org.eclipse.jetty.util.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*


private const val JETTY_WEBSOCKET_POOL_SIZE = 2000

private object JettyWebSocketPool : DefaultPool<ByteBuffer>(JETTY_WEBSOCKET_POOL_SIZE) {
    override fun produceInstance(): ByteBuffer = ByteBuffer.allocate(4096)!!

    override fun clearInstance(instance: ByteBuffer): ByteBuffer = instance.apply { clear() }
}

internal class EndPointReader(endpoint: EndPoint, context: CoroutineContext, private val channel: ByteWriteChannel)
    : AbstractConnection(endpoint, context.executor()), Connection.UpgradeTo {
    private val currentHandler = AtomicReference<Continuation<Unit>>()
    private val buffer = JettyWebSocketPool.borrow()

    init {
        runReader()
    }

    private fun runReader() = launch(Unconfined) {
        try {
            while (true) {
                buffer.clear()
                suspendCancellableCoroutine<Unit> { continuation ->
                    currentHandler.compareAndSet(null, continuation)
                    fillInterested()
                }

                channel.writeFully(buffer)
            }
        } catch (cause: Throwable) {
            if (cause !is ClosedChannelException) channel.close(cause)
        } finally {
            channel.close()
            JettyWebSocketPool.recycle(buffer)
        }
    }

    override fun onIdleExpired() = false

    override fun onFillable() {
        val handler = currentHandler.getAndSet(null)
        buffer.flip()
        val count = try {
            endPoint.fill(buffer)
        } catch (cause: Throwable) {
            handler.resumeWithException(ClosedChannelException())
        }

        if (count == -1) {
            handler.resumeWithException(ClosedChannelException())
        } else {
            handler.resume(Unit)
        }
    }

    override fun onFillInterestedFailed(cause: Throwable) {
        super.onFillInterestedFailed(cause)
        currentHandler.getAndSet(null)?.resumeWithException(cause)
    }

    override fun onUpgradeTo(prefilled: ByteBuffer?) {
        if (prefilled != null && prefilled.hasRemaining()) {
            // println("Got prefilled ${prefilled.remaining()} bytes")
            // TODO in theory client could try to start communication with no server upgrade acknowledge
            // it is generally not the case so it is not implemented yet
        }
    }
}

internal fun endPointWriter(
        endPoint: EndPoint,
        pool: ObjectPool<ByteBuffer> = JettyWebSocketPool
): ByteWriteChannel = reader(Unconfined, autoFlush = true) {
    pool.use { buffer ->
        endPoint.use { endPoint ->
            while (!channel.isClosedForRead) {
                buffer.clear()
                if (channel.readAvailable(buffer) == -1) break

                buffer.flip()
                endPoint.write(buffer)
            }
        }
    }
}.channel

private suspend fun EndPoint.write(buffer: ByteBuffer) = suspendCancellableCoroutine<Unit> { continuation ->
    write(object : Callback {
        override fun succeeded() {
            continuation.resume(Unit)
        }

        override fun failed(cause: Throwable) {
            continuation.resumeWithException(cause)
        }
    }, buffer)
}
