package net.corda.core.node.services

import net.corda.core.DoNotImplement
import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.messaging.DataFeed
import net.corda.core.transactions.SignedTransaction
import rx.Observable

/**
 * Thread-safe storage of transactions.
 */
@DoNotImplement
interface TransactionStorage {
    /**
     * Return the transaction with the given [id], or null if no such transaction exists.
     */
    fun getTransaction(id: SecureHash): SignedTransaction?

    /**
     * Get a synchronous Observable of updates.  When observations are pushed to the Observer, the vault will already
     * incorporate the update.
     */
    val updates: Observable<SignedTransaction>

    /**
     * Returns all currently stored transactions and further fresh ones.
     */
    fun track(): DataFeed<List<SignedTransaction>, SignedTransaction>

    /**
     * Returns a future that completes with the transaction corresponding to [id] once it has been committed
     */
    fun trackTransaction(id: SecureHash): CordaFuture<SignedTransaction>
}