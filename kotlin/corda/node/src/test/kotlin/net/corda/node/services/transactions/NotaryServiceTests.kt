package net.corda.node.services.transactions

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.*
import net.corda.core.flows.NotaryException
import net.corda.core.flows.NotaryFlow
import net.corda.core.identity.Party
import net.corda.core.internal.NotaryChangeTransactionBuilder
import net.corda.core.node.ServiceHub
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class NotaryServiceTests {
    private lateinit var mockNet: InternalMockNetwork
    private lateinit var notaryServices: ServiceHub
    private lateinit var aliceNode: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var notary: Party
    private lateinit var alice: Party

    @Before
    fun setup() {
        mockNet = InternalMockNetwork(
                cordappPackages = listOf("net.corda.testing.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(DUMMY_NOTARY_NAME, validating = false))
        )
        aliceNode = mockNet.createNode(InternalMockNodeParameters(legalName = ALICE_NAME))
        notaryServices = mockNet.defaultNotaryNode.services //TODO get rid of that
        notary = mockNet.defaultNotaryIdentity
        alice = aliceNode.services.myInfo.singleIdentity()
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `should reject a transaction with too many inputs`() {
        notariseWithTooManyInputs(aliceNode, alice, notary, mockNet)
    }

    internal companion object {
        /** This is used by both [NotaryServiceTests] and [ValidatingNotaryServiceTests]. */
        fun notariseWithTooManyInputs(node: StartedNode<InternalMockNetwork.MockNode>, party: Party, notary: Party, network: InternalMockNetwork) {
            val stx = generateTransaction(node, party, notary)

            val future = node.services.startFlow(DummyClientFlow(stx, notary)).resultFuture
            network.runNetwork()
            assertFailsWith<NotaryException> { future.getOrThrow() }
        }

        private fun generateTransaction(node: StartedNode<InternalMockNetwork.MockNode>, party: Party, notary: Party): SignedTransaction {
            val txHash = SecureHash.randomSHA256()
            val inputs = (1..10_005).map { StateRef(txHash, it) }
            val tx = NotaryChangeTransactionBuilder(inputs, notary, party).build()

            return node.services.run {
                val myKey = myInfo.legalIdentities.first().owningKey
                val signableData = SignableData(tx.id, SignatureMetadata(myInfo.platformVersion, Crypto.findSignatureScheme(myKey).schemeNumberID))
                val mySignature = keyManagementService.sign(signableData, myKey)
                SignedTransaction(tx, listOf(mySignature))
            }
        }

        private class DummyClientFlow(stx: SignedTransaction, val notary: Party) : NotaryFlow.Client(stx) {
            @Suspendable
            override fun call(): List<TransactionSignature> {
                notarise(notary)
                throw UnsupportedOperationException()
            }
        }
    }
}
