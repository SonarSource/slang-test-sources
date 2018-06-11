package net.corda.node.services.messaging

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.toStringShort
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.ThreadBox
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.MessageRecipients
import net.corda.core.messaging.SingleMessageRecipient
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.node.services.PartyInfo
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.serialization.serialize
import net.corda.core.utilities.*
import net.corda.node.VersionInfo
import net.corda.node.internal.LifecycleSupport
import net.corda.node.internal.artemis.ReactiveArtemisConsumer
import net.corda.node.internal.artemis.ReactiveArtemisConsumer.Companion.multiplex
import net.corda.node.services.api.NetworkMapCacheInternal
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.statemachine.DeduplicationId
import net.corda.node.services.statemachine.ExternalEvent
import net.corda.node.services.statemachine.SenderDeduplicationId
import net.corda.node.utilities.AffinityExecutor
import net.corda.node.utilities.PersistentMap
import net.corda.nodeapi.ArtemisTcpTransport.Companion.p2pConnectorTcpTransport
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisMessagingComponent.*
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.BRIDGE_CONTROL
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.BRIDGE_NOTIFY
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.JOURNAL_HEADER_SIZE
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2PMessagingHeaders
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.PEERS_PREFIX
import net.corda.nodeapi.internal.bridging.BridgeControl
import net.corda.nodeapi.internal.bridging.BridgeEntry
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import net.corda.nodeapi.internal.requireMessageSize
import org.apache.activemq.artemis.api.core.ActiveMQObjectClosedException
import org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID
import org.apache.activemq.artemis.api.core.Message.HDR_VALIDATED_USER
import org.apache.activemq.artemis.api.core.RoutingType
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.*
import org.apache.commons.lang.ArrayUtils.EMPTY_BYTE_ARRAY
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject
import java.io.Serializable
import java.security.PublicKey
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.ThreadSafe
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Lob

/**
 * This class implements the [MessagingService] API using Apache Artemis, the successor to their ActiveMQ product.
 * Artemis is a message queue broker and here we run a client connecting to the specified broker instance
 * [ArtemisMessagingServer]. It's primarily concerned with peer-to-peer messaging.
 *
 * Message handlers are run on the provided [AffinityExecutor] synchronously, that is, the Artemis callback threads
 * are blocked until the handler is scheduled and completed. This allows backpressure to propagate from the given
 * executor through into Artemis and from there, back through to senders.
 *
 * An implementation of [CordaRPCOps] can be provided. If given, clients using the CordaMQClient RPC library can
 * invoke methods on the provided implementation. There is more documentation on this in the docsite and the
 * CordaRPCClient class.
 *
 * @param config The configuration of the node, which is used for controlling the message redelivery options.
 * @param versionInfo All messages from the node carry the version info and received messages are checked against this for compatibility.
 * @param serverAddress The host and port of the Artemis broker.
 * @param myIdentity The primary identity of the node, which defines the messaging address for externally received messages.
 * It is also used to construct the myAddress field, which is ultimately advertised in the network map.
 * @param serviceIdentity An optional second identity if the node is also part of a group address, for example a notary.
 * @param nodeExecutor The received messages are marshalled onto the server executor to prevent Netty buffers leaking during fiber suspends.
 * @param database The nodes database, which is used to deduplicate messages.
 * @param advertisedAddress The externally advertised version of the Artemis broker address used to construct myAddress and included
 * in the network map data.
 * @param maxMessageSize A bound applied to the message size.
 */
@ThreadSafe
class P2PMessagingClient(val config: NodeConfiguration,
                         private val versionInfo: VersionInfo,
                         private val serverAddress: NetworkHostAndPort,
                         private val myIdentity: PublicKey,
                         private val serviceIdentity: PublicKey?,
                         private val nodeExecutor: AffinityExecutor.ServiceAffinityExecutor,
                         private val database: CordaPersistence,
                         private val networkMap: NetworkMapCacheInternal,
                         advertisedAddress: NetworkHostAndPort = serverAddress,
                         private val maxMessageSize: Int,
                         private val isDrainingModeOn: () -> Boolean,
                         private val drainingModeWasChangedEvents: Observable<Pair<Boolean, Boolean>>
) : SingletonSerializeAsToken(), MessagingService, AddressToArtemisQueueResolver, AutoCloseable {
    companion object {
        private val log = contextLogger()

        fun createMessageToRedeliver(): PersistentMap<Long, Pair<Message, MessageRecipients>, RetryMessage, Long> {
            return PersistentMap(
                    toPersistentEntityKey = { it },
                    fromPersistentEntity = {
                        Pair(it.key,
                                Pair(it.message.deserialize(context = SerializationDefaults.STORAGE_CONTEXT),
                                        it.recipients.deserialize(context = SerializationDefaults.STORAGE_CONTEXT))
                        )
                    },
                    toPersistentEntity = { _key: Long, (_message: Message, _recipient: MessageRecipients): Pair<Message, MessageRecipients> ->
                        RetryMessage().apply {
                            key = _key
                            message = _message.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                            recipients = _recipient.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                        }
                    },
                    persistentEntityClass = RetryMessage::class.java
            )
        }

        class NodeClientMessage(override val topic: String, override val data: ByteSequence, override val uniqueMessageId: DeduplicationId, override val senderUUID: String?, override val additionalHeaders: Map<String, String>) : Message {
            override val debugTimestamp: Instant = Instant.now()
            override fun toString() = "$topic#${String(data.bytes)}"
        }
    }

    private val messageMaxRetryCount: Int = config.p2pMessagingRetry.maxRetryCount
    private val backoffBase: Double = config.p2pMessagingRetry.backoffBase

    private class InnerState {
        var started = false
        var running = false
        var eventsSubscription: Subscription? = null
        var p2pConsumer: P2PMessagingConsumer? = null
        var locator: ServerLocator? = null
        var producer: ClientProducer? = null
        var producerSession: ClientSession? = null
        var bridgeSession: ClientSession? = null
        var bridgeNotifyConsumer: ClientConsumer? = null
        var networkChangeSubscription: Subscription? = null

        fun sendMessage(address: String, message: ClientMessage) = producer!!.send(address, message)
    }

    private val messagesToRedeliver = createMessageToRedeliver()

    private val scheduledMessageRedeliveries = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    /** A registration to handle messages of different types */
    data class HandlerRegistration(val topic: String, val callback: Any) : MessageHandlerRegistration

    override val myAddress: SingleMessageRecipient = NodeAddress(myIdentity, advertisedAddress)
    override val ourSenderUUID = UUID.randomUUID().toString()

    private val messageRedeliveryDelaySeconds = config.p2pMessagingRetry.messageRedeliveryDelay.seconds
    private val state = ThreadBox(InnerState())
    private val knownQueues = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val delayStartQueues = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val handlers = ConcurrentHashMap<String, MessageHandler>()

    private val deduplicator = P2PMessageDeduplicator(database)
    internal var messagingExecutor: MessagingExecutor? = null

    @Entity
    @javax.persistence.Table(name = "${NODE_DATABASE_PREFIX}message_retry")
    class RetryMessage(
            @Id
            @Column(name = "message_id", length = 64, nullable = false)
            var key: Long = 0,

            @Lob
            @Column(nullable = false)
            var message: ByteArray = EMPTY_BYTE_ARRAY,
            @Lob
            @Column(nullable = false)
            var recipients: ByteArray = EMPTY_BYTE_ARRAY
    ) : Serializable

    fun start() {
        state.locked {
            started = true
            log.info("Connecting to message broker: $serverAddress")
            // TODO Add broker CN to config for host verification in case the embedded broker isn't used
            val tcpTransport = p2pConnectorTcpTransport(serverAddress, config)
            locator = ActiveMQClient.createServerLocatorWithoutHA(tcpTransport).apply {
                // Never time out on our loopback Artemis connections. If we switch back to using the InVM transport this
                // would be the default and the two lines below can be deleted.
                connectionTTL = -1
                clientFailureCheckPeriod = -1
                minLargeMessageSize = maxMessageSize + JOURNAL_HEADER_SIZE
                isUseGlobalPools = nodeSerializationEnv != null
            }
            val sessionFactory = locator!!.createSessionFactory()
            // Login using the node username. The broker will authenticate us as its node (as opposed to another peer)
            // using our TLS certificate.
            // Note that the acknowledgement of messages is not flushed to the Artermis journal until the default buffer
            // size of 1MB is acknowledged.
            val createNewSession = { sessionFactory!!.createSession(ArtemisMessagingComponent.NODE_P2P_USER, ArtemisMessagingComponent.NODE_P2P_USER, false, true, true, locator!!.isPreAcknowledge, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE) }

            producerSession = createNewSession()
            bridgeSession = createNewSession()
            producerSession!!.start()
            bridgeSession!!.start()

            val inboxes = mutableSetOf<String>()
            // Create a queue, consumer and producer for handling P2P network messages.
            // Create a general purpose producer.
            producer = producerSession!!.createProducer()

            inboxes += RemoteInboxAddress(myIdentity).queueName
            serviceIdentity?.let {
                inboxes += RemoteInboxAddress(it).queueName
            }

            inboxes.forEach { createQueueIfAbsent(it, producerSession!!) }
            p2pConsumer = P2PMessagingConsumer(inboxes, createNewSession, isDrainingModeOn, drainingModeWasChangedEvents)

            messagingExecutor = MessagingExecutor(
                    producerSession!!,
                    producer!!,
                    versionInfo,
                    this@P2PMessagingClient,
                    ourSenderUUID = ourSenderUUID
            )

            registerBridgeControl(bridgeSession!!, inboxes.toList())
            enumerateBridges(bridgeSession!!, inboxes.toList())
        }

        resumeMessageRedelivery()
    }

    private fun InnerState.registerBridgeControl(session: ClientSession, inboxes: List<String>) {
        val bridgeNotifyQueue = "$BRIDGE_NOTIFY.${myIdentity.toStringShort()}"
        if (!session.queueQuery(SimpleString(bridgeNotifyQueue)).isExists) {
            session.createTemporaryQueue(BRIDGE_NOTIFY, RoutingType.MULTICAST, bridgeNotifyQueue)
        }
        val bridgeConsumer = session.createConsumer(bridgeNotifyQueue)
        bridgeNotifyConsumer = bridgeConsumer
        bridgeConsumer.setMessageHandler { msg ->
            state.locked {
                val data: ByteArray = ByteArray(msg.bodySize).apply { msg.bodyBuffer.readBytes(this) }
                val notifyMessage = data.deserialize<BridgeControl>(context = SerializationDefaults.P2P_CONTEXT)
                log.info(notifyMessage.toString())
                when (notifyMessage) {
                    is BridgeControl.BridgeToNodeSnapshotRequest -> enumerateBridges(session, inboxes)
                    else -> log.error("Unexpected Bridge Control message type on notify topic $notifyMessage")
                }
                msg.acknowledge()
            }
        }
        networkChangeSubscription = networkMap.changed.subscribe { updateBridgesOnNetworkChange(it) }
    }

    private fun sendBridgeControl(message: BridgeControl) {
        state.locked {
            val controlPacket = message.serialize(context = SerializationDefaults.P2P_CONTEXT).bytes
            val artemisMessage = producerSession!!.createMessage(false)
            artemisMessage.writeBodyBufferBytes(controlPacket)
            sendMessage(BRIDGE_CONTROL, artemisMessage)
        }
    }

    private fun updateBridgesOnNetworkChange(change: NetworkMapCache.MapChange) {
        log.info("Updating bridges on network map change: ${change.node}")
        fun gatherAddresses(node: NodeInfo): Sequence<BridgeEntry> {
            return state.locked {
                node.legalIdentitiesAndCerts.map {
                    val messagingAddress = NodeAddress(it.party.owningKey, node.addresses.first())
                    BridgeEntry(messagingAddress.queueName, node.addresses, node.legalIdentities.map { it.name })
                }.filter { producerSession!!.queueQuery(SimpleString(it.queueName)).isExists }.asSequence()
            }
        }

        fun deployBridges(node: NodeInfo) {
            gatherAddresses(node)
                    .forEach {
                        sendBridgeControl(BridgeControl.Create(myIdentity.toStringShort(), it))
                    }
        }

        fun destroyBridges(node: NodeInfo) {
            gatherAddresses(node)
                    .forEach {
                        sendBridgeControl(BridgeControl.Delete(myIdentity.toStringShort(), it))
                    }
        }

        when (change) {
            is NetworkMapCache.MapChange.Added -> {
                deployBridges(change.node)
            }
            is NetworkMapCache.MapChange.Removed -> {
                destroyBridges(change.node)
            }
            is NetworkMapCache.MapChange.Modified -> {
                destroyBridges(change.previousNode)
                deployBridges(change.node)
            }
        }
    }

    private fun enumerateBridges(session: ClientSession, inboxes: List<String>) {
        val requiredBridges = mutableListOf<BridgeEntry>()
        fun createBridgeEntry(queueName: SimpleString) {
            val keyHash = queueName.substring(PEERS_PREFIX.length)
            val peers = networkMap.getNodesByOwningKeyIndex(keyHash)
            for (node in peers) {
                val bridge = BridgeEntry(queueName.toString(), node.addresses, node.legalIdentities.map { it.name })
                requiredBridges += bridge
                knownQueues += queueName.toString()
            }
        }

        val queues = session.addressQuery(SimpleString("$PEERS_PREFIX#")).queueNames
        for (queue in queues) {
            val queueQuery = session.queueQuery(queue)
            if (!config.lazyBridgeStart || queueQuery.messageCount > 0) {
                createBridgeEntry(queue)
            } else {
                delayStartQueues += queue.toString()
            }
        }
        val startupMessage = BridgeControl.NodeToBridgeSnapshot(myIdentity.toStringShort(), inboxes, requiredBridges)
        sendBridgeControl(startupMessage)
    }

    private fun resumeMessageRedelivery() {
        messagesToRedeliver.forEach { retryId, (message, target) ->
            send(message, target, retryId)
        }
    }

    private val shutdownLatch = CountDownLatch(1)

    /**
     * Starts the p2p event loop: this method only returns once [stop] has been called.
     */
    fun run() {
        val latch = CountDownLatch(1)
        try {
            val consumer = state.locked {
                check(started) { "start must be called first" }
                check(!running) { "run can't be called twice" }
                running = true
                // If it's null, it means we already called stop, so return immediately.
                if (p2pConsumer == null) {
                    return
                }
                eventsSubscription = p2pConsumer!!.messages
                        .doOnError { error -> throw error }
                        .doOnNext { message -> deliver(message) }
                        // this `run()` method is semantically meant to block until the message consumption runs, hence the latch here
                        .doOnCompleted(latch::countDown)
                        .doOnError { error -> throw error }
                        .subscribe()
                p2pConsumer!!
            }
            consumer.start()
            latch.await()
        } finally {
            shutdownLatch.countDown()
        }
    }

    private fun artemisToCordaMessage(message: ClientMessage): ReceivedMessage? {
        try {
            requireMessageSize(message.bodySize, maxMessageSize)
            val topic = message.required(P2PMessagingHeaders.topicProperty) { getStringProperty(it) }
            val user = requireNotNull(message.getStringProperty(HDR_VALIDATED_USER)) { "Message is not authenticated" }
            val platformVersion = message.required(P2PMessagingHeaders.platformVersionProperty) { getIntProperty(it) }
            // Use the magic deduplication property built into Artemis as our message identity too
            val uniqueMessageId = message.required(HDR_DUPLICATE_DETECTION_ID) { DeduplicationId(message.getStringProperty(it)) }
            val receivedSenderUUID = message.getStringProperty(P2PMessagingHeaders.senderUUID)
            val receivedSenderSeqNo = if (message.containsProperty(P2PMessagingHeaders.senderSeqNo)) message.getLongProperty(P2PMessagingHeaders.senderSeqNo) else null
            val isSessionInit = message.getStringProperty(P2PMessagingHeaders.Type.KEY) == P2PMessagingHeaders.Type.SESSION_INIT_VALUE
            log.trace { "Received message from: ${message.address} user: $user topic: $topic id: $uniqueMessageId senderUUID: $receivedSenderUUID senderSeqNo: $receivedSenderSeqNo isSessionInit: $isSessionInit" }

            return ArtemisReceivedMessage(topic, CordaX500Name.parse(user), platformVersion, uniqueMessageId, receivedSenderUUID, receivedSenderSeqNo, isSessionInit, message)
        } catch (e: Exception) {
            log.error("Unable to process message, ignoring it: $message", e)
            return null
        }
    }

    private inline fun <T> ClientMessage.required(key: SimpleString, extractor: ClientMessage.(SimpleString) -> T): T {
        require(containsProperty(key)) { "Missing $key" }
        return extractor(key)
    }

    private class ArtemisReceivedMessage(override val topic: String,
                                         override val peer: CordaX500Name,
                                         override val platformVersion: Int,
                                         override val uniqueMessageId: DeduplicationId,
                                         override val senderUUID: String?,
                                         override val senderSeqNo: Long?,
                                         override val isSessionInit: Boolean,
                                         private val message: ClientMessage) : ReceivedMessage {
        override val data: ByteSequence by lazy { OpaqueBytes(ByteArray(message.bodySize).apply { message.bodyBuffer.readBytes(this) }) }
        override val debugTimestamp: Instant get() = Instant.ofEpochMilli(message.timestamp)
        override val additionalHeaders: Map<String, String> = emptyMap()
        override fun toString() = "$topic#$data"
    }

    internal fun deliver(artemisMessage: ClientMessage) {
        artemisToCordaMessage(artemisMessage)?.let { cordaMessage ->
            if (!deduplicator.isDuplicate(cordaMessage)) {
                deduplicator.signalMessageProcessStart(cordaMessage)
                deliver(cordaMessage, artemisMessage)
            } else {
                log.trace { "Discard duplicate message ${cordaMessage.uniqueMessageId} for ${cordaMessage.topic}" }
                messagingExecutor!!.acknowledge(artemisMessage)
            }
        }
    }

    private fun deliver(msg: ReceivedMessage, artemisMessage: ClientMessage) {
        state.checkNotLocked()
        val deliverTo = handlers[msg.topic]
        if (deliverTo != null) {
            try {
                deliverTo(msg, HandlerRegistration(msg.topic, deliverTo), MessageDeduplicationHandler(artemisMessage, msg))
            } catch (e: Exception) {
                log.error("Caught exception whilst executing message handler for ${msg.topic}", e)
            }
        } else {
            log.warn("Received message ${msg.uniqueMessageId} for ${msg.topic} that doesn't have any registered handlers yet")
        }
    }

    private inner class MessageDeduplicationHandler(val artemisMessage: ClientMessage, override val receivedMessage: ReceivedMessage) : DeduplicationHandler, ExternalEvent.ExternalMessageEvent {
        override val externalCause: ExternalEvent
            get() = this
        override val deduplicationHandler: MessageDeduplicationHandler
            get() = this

        override fun insideDatabaseTransaction() {
            deduplicator.persistDeduplicationId(receivedMessage.uniqueMessageId)
        }

        override fun afterDatabaseTransaction() {
            deduplicator.signalMessageProcessFinish(receivedMessage.uniqueMessageId)
            messagingExecutor!!.acknowledge(artemisMessage)
        }

        override fun toString(): String {
            return "${javaClass.simpleName}(${receivedMessage.uniqueMessageId})"
        }
    }

    /**
     * Initiates shutdown: if called from a thread that isn't controlled by the executor passed to the constructor
     * then this will block until all in-flight messages have finished being handled and acknowledged. If called
     * from a thread that's a part of the [net.corda.node.utilities.AffinityExecutor] given to the constructor,
     * it returns immediately and shutdown is asynchronous.
     */
    fun stop() {
        val running = state.locked {
            // We allow stop() to be called without a run() in between, but it must have at least been started.
            check(started)
            val prevRunning = running
            running = false
            networkChangeSubscription?.unsubscribe()
            require(p2pConsumer != null, { "stop can't be called twice" })
            require(producer != null, { "stop can't be called twice" })

            close(p2pConsumer)
            p2pConsumer = null

            close(producer)
            producer = null
            producerSession!!.commit()

            close(bridgeNotifyConsumer)
            knownQueues.clear()
            eventsSubscription?.unsubscribe()
            eventsSubscription = null
            prevRunning
        }
        if (running && !nodeExecutor.isOnThread) {
            // Wait for the main loop to notice the consumer has gone and finish up.
            shutdownLatch.await()
        }
        // Only first caller to gets running true to protect against double stop, which seems to happen in some integration tests.
        state.locked {
            locator?.close()
        }
    }

    private fun close(target: AutoCloseable?) {
        try {
            target?.close()
        } catch (ignored: ActiveMQObjectClosedException) {
            // swallow
        }
    }

    override fun close() = stop()

    @Suspendable
    override fun send(message: Message, target: MessageRecipients, retryId: Long?, sequenceKey: Any) {
        requireMessageSize(message.data.size, maxMessageSize)
        messagingExecutor!!.send(message, target)
        retryId?.let {
            database.transaction {
                messagesToRedeliver.computeIfAbsent(it, { Pair(message, target) })
            }
            scheduledMessageRedeliveries[it] = nodeExecutor.schedule({
                sendWithRetry(0, message, target, retryId)
            }, messageRedeliveryDelaySeconds, TimeUnit.SECONDS)
        }
    }

    @Suspendable
    override fun send(addressedMessages: List<MessagingService.AddressedMessage>) {
        for ((message, target, retryId, sequenceKey) in addressedMessages) {
            send(message, target, retryId, sequenceKey)
        }
    }

    private fun sendWithRetry(retryCount: Int, message: Message, target: MessageRecipients, retryId: Long) {
        log.trace { "Attempting to retry #$retryCount message delivery for $retryId" }
        if (retryCount >= messageMaxRetryCount) {
            log.warn("Reached the maximum number of retries ($messageMaxRetryCount) for message $message redelivery to $target")
            scheduledMessageRedeliveries.remove(retryId)
            return
        }

        val messageWithRetryCount = object : Message by message {
            override val uniqueMessageId = DeduplicationId("${message.uniqueMessageId.toString}-$retryCount")
        }

        messagingExecutor!!.send(messageWithRetryCount, target)

        scheduledMessageRedeliveries[retryId] = nodeExecutor.schedule({
            sendWithRetry(retryCount + 1, message, target, retryId)
        }, messageRedeliveryDelaySeconds * Math.pow(backoffBase, retryCount.toDouble()).toLong(), TimeUnit.SECONDS)
    }

    override fun cancelRedelivery(retryId: Long) {
        database.transaction {
            messagesToRedeliver.remove(retryId)
        }
        scheduledMessageRedeliveries[retryId]?.let {
            log.trace { "Cancelling message redelivery for retry id $retryId" }
            if (!it.isDone) it.cancel(true)
            scheduledMessageRedeliveries.remove(retryId)
        }
    }

    override fun resolveTargetToArtemisQueue(address: MessageRecipients): String {
        return if (address == myAddress) {
            // If we are sending to ourselves then route the message directly to our P2P queue.
            RemoteInboxAddress(myIdentity).queueName
        } else {
            // Otherwise we send the message to an internal queue for the target residing on our broker. It's then the
            // broker's job to route the message to the target's P2P queue.
            val internalTargetQueue = (address as? ArtemisAddress)?.queueName
                    ?: throw IllegalArgumentException("Not an Artemis address")
            state.locked {
                createQueueIfAbsent(internalTargetQueue, producerSession!!)
            }
            internalTargetQueue
        }
    }

    /** Attempts to create a durable queue on the broker which is bound to an address of the same name. */
    private fun createQueueIfAbsent(queueName: String, session: ClientSession) {
        fun sendBridgeCreateMessage() {
            val keyHash = queueName.substring(PEERS_PREFIX.length)
            val peers = networkMap.getNodesByOwningKeyIndex(keyHash)
            for (node in peers) {
                val bridge = BridgeEntry(queueName, node.addresses, node.legalIdentities.map { it.name })
                val createBridgeMessage = BridgeControl.Create(myIdentity.toStringShort(), bridge)
                sendBridgeControl(createBridgeMessage)
            }
        }
        if (!knownQueues.contains(queueName)) {
            if (delayStartQueues.contains(queueName)) {
                log.info("Start bridge for previously empty queue $queueName")
                sendBridgeCreateMessage()
                delayStartQueues -= queueName
            } else {
                val queueQuery = session.queueQuery(SimpleString(queueName))
                if (!queueQuery.isExists) {
                    log.info("Create fresh queue $queueName bound on same address")
                    session.createQueue(queueName, RoutingType.ANYCAST, queueName, true)
                    sendBridgeCreateMessage()
                }
            }
            knownQueues += queueName
        }
    }

    override fun addMessageHandler(topic: String, callback: MessageHandler): MessageHandlerRegistration {
        require(!topic.isBlank()) { "Topic must not be blank, as the empty topic is a special case." }
        handlers.compute(topic) { _, handler ->
            if (handler != null) {
                throw IllegalStateException("Cannot add another acking handler for $topic, there is already an acking one")
            }
            callback
        }
        return HandlerRegistration(topic, callback)
    }

    override fun removeMessageHandler(registration: MessageHandlerRegistration) {
        registration as HandlerRegistration
        handlers.remove(registration.topic)
    }

    override fun createMessage(topic: String, data: ByteArray, deduplicationId: SenderDeduplicationId, additionalHeaders: Map<String, String>): Message {
        return NodeClientMessage(topic, OpaqueBytes(data), deduplicationId.deduplicationId, deduplicationId.senderUUID, additionalHeaders)
    }

    override fun getAddressOfParty(partyInfo: PartyInfo): MessageRecipients {
        return when (partyInfo) {
            is PartyInfo.SingleNode -> NodeAddress(partyInfo.party.owningKey, partyInfo.addresses.single())
            is PartyInfo.DistributedNode -> ServiceAddress(partyInfo.party.owningKey)
        }
    }
}

private class P2PMessagingConsumer(
        queueNames: Set<String>,
        createSession: () -> ClientSession,
        private val isDrainingModeOn: () -> Boolean,
        private val drainingModeWasChangedEvents: Observable<Pair<Boolean, Boolean>>) : LifecycleSupport {

    private companion object {
        private const val initialSessionMessages = "${P2PMessagingHeaders.Type.KEY}<>'${P2PMessagingHeaders.Type.SESSION_INIT_VALUE}'"
    }

    private var startedFlag = false

    val messages: PublishSubject<ClientMessage> = PublishSubject.create<ClientMessage>()

    private val existingOnlyConsumer = multiplex(queueNames, createSession, initialSessionMessages)
    private val initialAndExistingConsumer = multiplex(queueNames, createSession)
    private val subscriptions = mutableSetOf<Subscription>()

    override fun start() {

        synchronized(this) {
            require(!startedFlag)
            drainingModeWasChangedEvents.filter { change -> change.switchedOn() }.doOnNext { initialAndExistingConsumer.switchTo(existingOnlyConsumer) }.subscribe()
            drainingModeWasChangedEvents.filter { change -> change.switchedOff() }.doOnNext { existingOnlyConsumer.switchTo(initialAndExistingConsumer) }.subscribe()
            subscriptions += existingOnlyConsumer.messages.doOnNext(messages::onNext).subscribe()
            subscriptions += initialAndExistingConsumer.messages.doOnNext(messages::onNext).subscribe()
            if (isDrainingModeOn()) {
                existingOnlyConsumer.start()
            } else {
                initialAndExistingConsumer.start()
            }
            startedFlag = true
        }
    }

    override fun stop() {

        synchronized(this) {
            if (startedFlag) {
                existingOnlyConsumer.stop()
                initialAndExistingConsumer.stop()
                subscriptions.forEach(Subscription::unsubscribe)
                subscriptions.clear()
                startedFlag = false
            }
            messages.onCompleted()
        }
    }

    override val started: Boolean
        get() = startedFlag

    private fun Pair<Boolean, Boolean>.switchedOff() = first && !second

    private fun Pair<Boolean, Boolean>.switchedOn() = !first && second
}

private fun ReactiveArtemisConsumer.switchTo(other: ReactiveArtemisConsumer) {

    disconnect()
    when {
        !other.started -> other.start()
        !other.connected -> other.connect()
    }
}
