package io.ktor.client.engine.apache

import io.ktor.client.utils.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import org.apache.http.*
import org.apache.http.entity.*
import org.apache.http.nio.*
import org.apache.http.nio.protocol.*
import org.apache.http.protocol.*
import java.nio.ByteBuffer
import kotlin.coroutines.experimental.*


private const val MAX_QUEUE_LENGTH: Int = 65 * 1024 / DEFAULT_HTTP_BUFFER_SIZE

internal class ApacheResponseConsumer(
    private val dispatcher: CoroutineContext,
    private val parent: CompletableDeferred<Unit>,
    private val block: (HttpResponse, ByteReadChannel) -> Unit
) : AbstractAsyncResponseConsumer<Unit>() {
    private val channel = ByteChannel()
    private val backendChannel = Channel<ByteBuffer>(MAX_QUEUE_LENGTH)
    private var current: ByteBuffer = HttpClientDefaultPool.borrow()

    init {
        runResponseProcessing()
    }

    override fun onResponseReceived(response: HttpResponse) = block(response, channel)

    override fun releaseResources() {
        backendChannel.close()
    }

    override fun buildResult(context: HttpContext) = Unit

    override fun onContentReceived(decoder: ContentDecoder, ioctrl: IOControl) {
        val read = try {
            decoder.read(current)
        } catch (cause: Throwable) {
            backendChannel.close(cause)
            return
        }

        if (read <= 0 || current.hasRemaining()) return

        current.flip()
        if (!backendChannel.offer(current)) {
            launch(Unconfined) {
                ioctrl.suspendInput()
                try {
                    backendChannel.send(current)
                } catch (cause: Throwable) {
                } finally {
                    ioctrl.requestInput()
                }
            }
        }

        current = HttpClientDefaultPool.borrow()
    }

    override fun onEntityEnclosed(entity: HttpEntity, contentType: ContentType) {}

    private fun runResponseProcessing() = launch(dispatcher) {
        try {
            while (!backendChannel.isClosedForReceive) {
                val buffer = backendChannel.receiveOrNull() ?: break
                channel.writeFully(buffer)
                HttpClientDefaultPool.recycle(buffer)
            }

            channel.writeRemaining()
        } catch (cause: Throwable) {
            channel.close(cause)
            parent.completeExceptionally(cause)
        } finally {
            channel.close()
            parent.complete(Unit)
            HttpClientDefaultPool.recycle(current)
        }
    }

    private suspend fun ByteWriteChannel.writeRemaining() {
        current.flip()
        if (current.hasRemaining()) writeFully(current)
    }
}
