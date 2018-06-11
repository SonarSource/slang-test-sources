package net.corda.finance.flows

import net.corda.core.identity.Party
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.Cash
import net.corda.testing.core.BOC_NAME
import net.corda.testing.node.InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashIssueFlowTests {
    private lateinit var mockNet: MockNetwork
    private lateinit var bankOfCordaNode: StartedMockNode
    private lateinit var bankOfCorda: Party
    private lateinit var notary: Party

    @Before
    fun start() {
        mockNet = MockNetwork(servicePeerAllocationStrategy = RoundRobin(), cordappPackages = listOf("net.corda.finance.contracts.asset"))
        bankOfCordaNode = mockNet.createPartyNode(BOC_NAME)
        bankOfCorda = bankOfCordaNode.info.identityFromX500Name(BOC_NAME)
        notary = mockNet.defaultNotaryIdentity
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `issue some cash`() {
        val expected = 500.DOLLARS
        val ref = OpaqueBytes.of(0x01)
        val future = bankOfCordaNode.startFlow(CashIssueFlow(expected, ref, notary))
        mockNet.runNetwork()
        val issueTx = future.getOrThrow().stx
        val output = issueTx.tx.outputsOfType<Cash.State>().single()
        assertEquals(expected.`issued by`(bankOfCorda.ref(ref)), output.amount)
    }

    @Test
    fun `issue zero cash`() {
        val expected = 0.DOLLARS
        val ref = OpaqueBytes.of(0x01)
        val future = bankOfCordaNode.startFlow(CashIssueFlow(expected, ref, notary))
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> {
            future.getOrThrow()
        }
    }
}
