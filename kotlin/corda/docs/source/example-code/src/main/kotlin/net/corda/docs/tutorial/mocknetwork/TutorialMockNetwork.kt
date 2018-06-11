package net.corda.docs.tutorial.mocknetwork

import co.paralleluniverse.fibers.Suspendable
import com.google.common.collect.ImmutableList
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExpectedException

class TutorialMockNetwork {

    @InitiatingFlow
    class FlowA(private val otherParty: Party) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val session = initiateFlow(otherParty)

            session.receive<Int>().unwrap {
                requireThat { "Expected to receive 1" using (it == 1) }
            }

            session.receive<Int>().unwrap {
                requireThat { "Expected to receive 2" using (it == 2) }
            }
        }
    }

    @InitiatedBy(FlowA::class)
    class FlowB(private val session: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            session.send(1)
            session.send(2)
        }
    }

    private lateinit var mockNet: MockNetwork
    private lateinit var nodeA: StartedMockNode
    private lateinit var nodeB: StartedMockNode

    @Rule
    @JvmField
    val expectedEx: ExpectedException = ExpectedException.none()

    @Before
    fun setUp() {
        mockNet = MockNetwork(ImmutableList.of("net.corda.docs.tutorial.mocknetwork"))
        nodeA = mockNet.createPartyNode()
        nodeB = mockNet.createPartyNode()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

//    @Test
//    fun `fail if initiated doesn't send back 1 on first result`() {

    // DOCSTART 1
    // TODO: Fix this test - accessing the MessagingService directly exposes internal interfaces
//        nodeB.setMessagingServiceSpy(object : MessagingServiceSpy(nodeB.network) {
//            override fun send(message: Message, target: MessageRecipients, retryId: Long?, sequenceKey: Any, additionalHeaders: Map<String, String>) {
//                val messageData = message.data.deserialize<Any>() as? ExistingSessionMessage
//                val payload = messageData?.payload
//
//                if (payload is DataSessionMessage && payload.payload.deserialize() == 1) {
//                    val alteredMessageData = messageData.copy(payload = payload.copy(99.serialize())).serialize().bytes
//                    messagingService.send(InMemoryMessagingNetwork.InMemoryMessage(message.topic, OpaqueBytes(alteredMessageData), message.uniqueMessageId), target, retryId)
//                } else {
//                    messagingService.send(message, target, retryId)
//                }
//            }
//        })
    // DOCEND 1

//        val initiatingReceiveFlow = nodeA.startFlow(FlowA(nodeB.info.legalIdentities.first()))
//
//        mockNet.runNetwork()
//
//        expectedEx.expect(IllegalArgumentException::class.java)
//        expectedEx.expectMessage("Expected to receive 1")
//        initiatingReceiveFlow.getOrThrow()
//    }
}