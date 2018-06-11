package io.ktor.server.netty.http2

import io.netty.buffer.*
import io.netty.handler.codec.http2.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*

internal suspend fun ReceiveChannel<Http2DataFrame>.http2frameLoop(bc: ByteWriteChannel) {
    try {
        while (!isClosedForReceive) {
            val message = receiveOrNull() ?: break
            val content = message.content() ?: Unpooled.EMPTY_BUFFER

            while (content.readableBytes() > 0) {
                bc.write { bb ->
                    val size = content.readableBytes()
                    if (bb.remaining() > size) {
                        val l = bb.limit()
                        bb.limit(bb.position() + size)
                        content.readBytes(bb)
                        bb.limit(l)
                    } else {
                        content.readBytes(bb)
                    }
                }
            }

            bc.flush()
            content.release()

            if (message.isEndStream) {
                break
            }
        }
    } catch (t: Throwable) {
        bc.close(t)
    } finally {
        bc.close()
    }
}