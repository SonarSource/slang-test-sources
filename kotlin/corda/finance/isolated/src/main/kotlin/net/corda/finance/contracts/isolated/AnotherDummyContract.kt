package net.corda.finance.contracts.isolated

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.nodeapi.DummyContractBackdoor

const val ANOTHER_DUMMY_PROGRAM_ID = "net.corda.finance.contracts.isolated.AnotherDummyContract"

@Suppress("UNUSED")
class AnotherDummyContract : Contract, DummyContractBackdoor {
    val magicString = "helloworld"

    data class State(val magicNumber: Int = 0) : ContractState {
        override val participants: List<AbstractParty>
            get() = emptyList()
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        // Always accepts.
    }

    override fun generateInitial(owner: PartyAndReference, magicNumber: Int, notary: Party): TransactionBuilder {
        val state = State(magicNumber)
        return TransactionBuilder(notary).withItems(StateAndContract(state, ANOTHER_DUMMY_PROGRAM_ID), Command(Commands.Create(), owner.party.owningKey))
    }

    override fun inspectState(state: ContractState): Int = (state as State).magicNumber
}