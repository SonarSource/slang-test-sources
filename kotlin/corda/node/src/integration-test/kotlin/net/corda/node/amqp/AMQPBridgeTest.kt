package net.corda.node.amqp

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.crypto.toStringShort
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import net.corda.node.services.config.CertChainPolicyConfig
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.ArtemisMessagingClient
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2PMessagingHeaders
import net.corda.nodeapi.internal.bridging.AMQPBridgeManager
import net.corda.nodeapi.internal.bridging.BridgeManager
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPServer
import net.corda.testing.core.*
import net.corda.testing.internal.rigorousMock
import org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID
import org.apache.activemq.artemis.api.core.RoutingType
import org.apache.activemq.artemis.api.core.SimpleString
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*
import kotlin.test.assertEquals

class AMQPBridgeTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val log = loggerFor<AMQPBridgeTest>()

    private val BOB = TestIdentity(BOB_NAME)

    private val artemisPort = freePort()
    private val artemisPort2 = freePort()
    private val amqpPort = freePort()
    private val artemisAddress = NetworkHostAndPort("localhost", artemisPort)
    private val amqpAddress = NetworkHostAndPort("localhost", amqpPort)

    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Test
    fun `test acked and nacked messages`() {
        // Create local queue
        val sourceQueueName = "internal.peers." + BOB.publicKey.toStringShort()
        val (artemisServer, artemisClient, bridgeManager) = createArtemis(sourceQueueName)

        // Pre-populate local queue with 3 messages
        val artemis = artemisClient.started!!
        for (i in 0 until 3) {
            val artemisMessage = artemis.session.createMessage(true).apply {
                putIntProperty(P2PMessagingHeaders.senderUUID, i)
                writeBodyBufferBytes("Test$i".toByteArray())
                // Use the magic deduplication property built into Artemis as our message identity too
                putStringProperty(HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
            }
            artemis.producer.send(sourceQueueName, artemisMessage)
        }

        //Create target server
        val amqpServer = createAMQPServer()
        val dedupeSet = mutableSetOf<String>()

        val receive = amqpServer.onReceive.toBlocking().iterator
        amqpServer.start()

        val receivedSequence = mutableListOf<Int>()
        val atNodeSequence = mutableListOf<Int>()

        fun formatMessage(expected: String, actual: Int, received: List<Int>): String {
            return "Expected message with id $expected, got $actual, previous message receive sequence: " +
                    "${received.joinToString(",  ", "[", "]")}."
        }

        val received1 = receive.next()
        val messageID1 = received1.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
        assertArrayEquals("Test$messageID1".toByteArray(), received1.payload)
        assertEquals(0, messageID1)
        dedupeSet += received1.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
        received1.complete(true) // Accept first message
        receivedSequence += messageID1
        atNodeSequence += messageID1

        val received2 = receive.next()
        val messageID2 = received2.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
        assertArrayEquals("Test$messageID2".toByteArray(), received2.payload)
        assertEquals(1, messageID2, formatMessage("1", messageID2, receivedSequence))
        received2.complete(false) // Reject message and don't add to dedupe
        receivedSequence += messageID2 // reflects actual sequence

        // drop things until we get back to the replay
        while (true) {
            val received3 = receive.next()
            val messageID3 = received3.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID3".toByteArray(), received3.payload)
            receivedSequence += messageID3
            if (messageID3 != 1) { // keep rejecting any batched items following rejection
                received3.complete(false)
            } else { // beginnings of replay so accept again
                received3.complete(true)
                val messageId = received3.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
                if (messageId !in dedupeSet) {
                    dedupeSet += messageId
                    atNodeSequence += messageID3
                }
                break
            }
        }

        // start receiving again, but discarding duplicates
        while (true) {
            val received4 = receive.next()
            val messageID4 = received4.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID4".toByteArray(), received4.payload)
            receivedSequence += messageID4
            val messageId = received4.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
            if (messageId !in dedupeSet) {
                dedupeSet += messageId
                atNodeSequence += messageID4
            }
            received4.complete(true)
            if (messageID4 == 2) { // started to replay messages after rejection point
                break
            }
        }

        // Send a fresh item and check receive
        val artemisMessage = artemis.session.createMessage(true).apply {
            putIntProperty(P2PMessagingHeaders.senderUUID, 3)
            writeBodyBufferBytes("Test3".toByteArray())
            // Use the magic deduplication property built into Artemis as our message identity too
            putStringProperty(HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
        }
        artemis.producer.send(sourceQueueName, artemisMessage)


        // start receiving again, discarding duplicates
        while (true) {
            val received5 = receive.next()
            val messageID5 = received5.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID5".toByteArray(), received5.payload)
            receivedSequence += messageID5
            val messageId = received5.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
            if (messageId !in dedupeSet) {
                dedupeSet += messageId
                atNodeSequence += messageID5
            }
            received5.complete(true)
            if (messageID5 == 3) { // reached our fresh message
                break
            }
        }

        log.info("Message sequence: ${receivedSequence.joinToString(", ", "[", "]")}")
        log.info("Deduped sequence: ${atNodeSequence.joinToString(", ", "[", "]")}")
        assertEquals(listOf(0, 1, 2, 3), atNodeSequence)
        bridgeManager.stop()
        amqpServer.stop()
        artemisClient.stop()
        artemisServer.stop()
    }

    private fun createArtemis(sourceQueueName: String?): Triple<ArtemisMessagingServer, ArtemisMessagingClient, BridgeManager> {
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "artemis").whenever(it).baseDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn(true).whenever(it).crlCheckSoftFail
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn(artemisAddress).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
        }
        artemisConfig.configureWithDevSSLCertificate()
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", artemisPort), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig, artemisAddress, MAX_MESSAGE_SIZE)
        artemisServer.start()
        artemisClient.start()
        val bridgeManager = AMQPBridgeManager(artemisConfig, artemisAddress, MAX_MESSAGE_SIZE)
        bridgeManager.start()
        val artemis = artemisClient.started!!
        if (sourceQueueName != null) {
            // Local queue for outgoing messages
            artemis.session.createQueue(sourceQueueName, RoutingType.ANYCAST, sourceQueueName, true)
            bridgeManager.deployBridge(sourceQueueName, amqpAddress, setOf(BOB.name))
        }
        return Triple(artemisServer, artemisClient, bridgeManager)
    }

    private fun createAMQPServer(maxMessageSize: Int = MAX_MESSAGE_SIZE): AMQPServer {
        val serverConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "server").whenever(it).baseDirectory
            doReturn(BOB_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
        }
        serverConfig.configureWithDevSSLCertificate()

        return AMQPServer("0.0.0.0",
                amqpPort,
                ArtemisMessagingComponent.PEER_USER,
                ArtemisMessagingComponent.PEER_USER,
                serverConfig.loadSslKeyStore().internal,
                serverConfig.keyStorePassword,
                serverConfig.loadTrustStore().internal,
                crlCheckSoftFail = true,
                trace = true,
                maxMessageSize = maxMessageSize
        )
    }
}