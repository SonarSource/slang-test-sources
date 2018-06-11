package net.corda.core.concurrent

import net.corda.core.serialization.CordaSerializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * Same as [Future] with additional methods to provide some of the features of [java.util.concurrent.CompletableFuture] while minimising the API surface area.
 * In Kotlin, to avoid compile errors, whenever CordaFuture is used in a parameter or extension method receiver type, its type parameter should be specified with out variance.
 */
@CordaSerializable
interface CordaFuture<V> : Future<V> {
    /**
     * Run the given callback when this future is done, on the completion thread.
     * If the completion thread is problematic for you e.g. deadlock, you can submit to an executor manually.
     * If callback fails, its throwable is logged.
     */
    fun <W> then(callback: (CordaFuture<V>) -> W)

    /**
     * @return a new [CompletableFuture] with the same outcome as this Future.
     */
    fun toCompletableFuture(): CompletableFuture<V>
}
