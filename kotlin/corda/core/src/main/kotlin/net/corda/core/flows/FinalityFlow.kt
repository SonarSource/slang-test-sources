package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.isFulfilledBy
import net.corda.core.identity.Party
import net.corda.core.identity.groupAbstractPartyByWellKnownParty
import net.corda.core.internal.pushToLoggingContext
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

/**
 * Verifies the given transaction, then sends it to the named notary. If the notary agrees that the transaction
 * is acceptable then it is from that point onwards committed to the ledger, and will be written through to the
 * vault. Additionally it will be distributed to the parties reflected in the participants list of the states.
 *
 * The transaction is expected to have already been resolved: if its dependencies are not available in local
 * storage, verification will fail. It must have signatures from all necessary parties other than the notary.
 *
 * If specified, the extra recipients are sent the given transaction. The base set of parties to inform are calculated
 * from the contract-given set of participants.
 *
 * The flow returns the same transaction but with the additional signatures from the notary.
 *
 * @param transaction What to commit.
 * @param extraRecipients A list of additional participants to inform of the transaction.
 */
@InitiatingFlow
class FinalityFlow(val transaction: SignedTransaction,
                   private val extraRecipients: Set<Party>,
                   override val progressTracker: ProgressTracker) : FlowLogic<SignedTransaction>() {
    constructor(transaction: SignedTransaction, extraParticipants: Set<Party>) : this(transaction, extraParticipants, tracker())
    constructor(transaction: SignedTransaction) : this(transaction, emptySet(), tracker())
    constructor(transaction: SignedTransaction, progressTracker: ProgressTracker) : this(transaction, emptySet(), progressTracker)

    companion object {
        object NOTARISING : ProgressTracker.Step("Requesting signature by notary service") {
            override fun childProgressTracker() = NotaryFlow.Client.tracker()
        }

        object BROADCASTING : ProgressTracker.Step("Broadcasting transaction to participants")

        @JvmStatic
        fun tracker() = ProgressTracker(NOTARISING, BROADCASTING)
    }

    @Suspendable
    @Throws(NotaryException::class)
    override fun call(): SignedTransaction {
        // Note: this method is carefully broken up to minimize the amount of data reachable from the stack at
        // the point where subFlow is invoked, as that minimizes the checkpointing work to be done.
        //
        // Lookup the resolved transactions and use them to map each signed transaction to the list of participants.
        // Then send to the notary if needed, record locally and distribute.

        transaction.pushToLoggingContext()
        val commandDataTypes = transaction.tx.commands.map { it.value }.mapNotNull { it::class.qualifiedName }.distinct()
        logger.info("Started finalization, commands are ${commandDataTypes.joinToString(", ", "[", "]")}.")
        val parties = getPartiesToSend(verifyTx())
        val notarised = notariseAndRecord()

        // Each transaction has its own set of recipients, but extra recipients get them all.
        progressTracker.currentStep = BROADCASTING
        val recipients = parties.filterNot(serviceHub.myInfo::isLegalIdentity)
        logger.info("Broadcasting transaction to parties ${recipients.map { it.name }.joinToString(", ", "[", "]")}.")
        for (party in recipients) {
            logger.info("Sending transaction to party ${party.name}.")
            val session = initiateFlow(party)
            subFlow(SendTransactionFlow(session, notarised))
            logger.info("Party ${party.name} received the transaction.")
        }
        logger.info("All parties received the transaction successfully.")

        return notarised
    }

    @Suspendable
    private fun notariseAndRecord(): SignedTransaction {
        val notarised = if (needsNotarySignature(transaction)) {
            progressTracker.currentStep = NOTARISING
            val notarySignatures = subFlow(NotaryFlow.Client(transaction))
            transaction + notarySignatures
        } else {
            logger.info("No need to notarise this transaction.")
            transaction
        }
        logger.info("Recording transaction locally.")
        serviceHub.recordTransactions(notarised)
        logger.info("Recorded transaction locally successfully.")
        return notarised
    }

    private fun needsNotarySignature(stx: SignedTransaction): Boolean {
        val wtx = stx.tx
        val needsNotarisation = wtx.inputs.isNotEmpty() || wtx.timeWindow != null
        return needsNotarisation && hasNoNotarySignature(stx)
    }

    private fun hasNoNotarySignature(stx: SignedTransaction): Boolean {
        val notaryKey = stx.tx.notary?.owningKey
        val signers = stx.sigs.map { it.by }.toSet()
        return notaryKey?.isFulfilledBy(signers) != true
    }

    private fun getPartiesToSend(ltx: LedgerTransaction): Set<Party> {
        val participants = ltx.outputStates.flatMap { it.participants } + ltx.inputStates.flatMap { it.participants }
        return groupAbstractPartyByWellKnownParty(serviceHub, participants).keys + extraRecipients
    }

    private fun verifyTx(): LedgerTransaction {
        val notary = transaction.tx.notary
        // The notary signature(s) are allowed to be missing but no others.
        if (notary != null) transaction.verifySignaturesExcept(notary.owningKey) else transaction.verifyRequiredSignatures()
        val ltx = transaction.toLedgerTransaction(serviceHub, false)
        ltx.verify()
        return ltx
    }
}
