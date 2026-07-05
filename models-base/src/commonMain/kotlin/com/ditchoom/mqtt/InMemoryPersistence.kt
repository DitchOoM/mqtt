package com.ditchoom.mqtt

import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryPersistence : Persistence {
    /**
     * Serializes every read/modify/write of the shared maps below and of [nextPacketId].
     * Client publish/subscribe/unsubscribe calls and the inbound read loop all run on
     * [kotlinx.coroutines.Dispatchers.Default] (genuinely multi-threaded), so without this
     * two concurrent QoS>0 publishes could race in [getPacketId] and be assigned the SAME
     * packet identifier — verified on the wire against the paho conformance broker
     * (DitchOoM/mqtt#12). All methods are already `suspend`, so a non-reentrant [Mutex] is
     * clean; internal cross-method calls are inlined to avoid self-deadlock.
     */
    private val mutex = Mutex()

    private var nextPacketId = 0.toUShort()

    private val activeSubscriptions = HashMap<Int, MutableMap<TopicFilter, ISubscription>>()

    // client messages
    private val clientMessages = HashMap<Int, MutableMap<Int, ControlPacket>>()

    // server messages
    private val serverMessages = HashMap<Int, MutableMap<Int, ControlPacket>>()

    // persisted incoming QoS 1/2 PUBLISH payloads awaiting handler completion or redelivery
    private val incomingPublishes = HashMap<Int, MutableMap<Int, IncomingPublishEntry>>()

    private val brokers = mutableMapOf<Int, MqttBroker>()

    private data class IncomingPublishEntry(
        val packet: PublishMessage,
        var state: Int,
    )

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean,
    ): Map<TopicFilter, ISubscription> = mutex.withLock { activeSubscriptions[broker.identifier] ?: emptyMap() }

    override suspend fun clearMessages(broker: MqttBroker) =
        mutex.withLock {
            clientMessages.clear()
            incomingPublishes[broker.identifier]?.clear()
            Unit
        }

    override suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: PublishMessage,
    ): Int =
        mutex.withLock {
            val packetId = getPacketId()
            val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            clientMessagesForBroker[packetId] = pub.maybeCopyWithNewPacketIdentifier(packetId)
            packetId
        }

    override suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): PublishMessage? = mutex.withLock { clientMessages[broker.identifier]?.get(packetId) as? PublishMessage }

    override suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int =
        mutex.withLock {
            val packetId = getPacketId()
            val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            clientMessagesForBroker[packetId] = unsub.copyWithNewPacketIdentifier(packetId)
            packetId
        }

    override suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest? = mutex.withLock { clientMessages[broker.identifier]?.get(packetId) as? IUnsubscribeRequest }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> =
        mutex.withLock {
            val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            val clientMap = LinkedHashMap<Int, ControlPacket>()
            clientMessagesForBroker.forEach { (key, value) ->
                clientMap[key] =
                    if (value is PublishMessage) {
                        value.setDupFlagNewPubMessage()
                    } else {
                        value
                    }
            }
            val serverMessagesForBroker = serverMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            (clientMap.values + serverMessagesForBroker.values).sortedBy { it.packetIdentifier }
        }

    override suspend fun persistIncomingPublish(
        broker: MqttBroker,
        packet: PublishMessage,
    ) {
        if (packet.qualityOfService == QualityOfService.AT_MOST_ONCE) return
        mutex.withLock {
            val map = incomingPublishes.getOrPut(broker.identifier) { LinkedHashMap() }
            map[packet.packetIdentifier] =
                IncomingPublishEntry(packet, Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER)
        }
    }

    override suspend fun incomingHandlerComplete(
        broker: MqttBroker,
        packetId: Int,
    ) = mutex.withLock {
        val map = incomingPublishes[broker.identifier] ?: return@withLock
        val entry = map[packetId] ?: return@withLock
        when (entry.packet.qualityOfService) {
            QualityOfService.AT_LEAST_ONCE -> map.remove(packetId)
            QualityOfService.EXACTLY_ONCE ->
                entry.state = Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT
            QualityOfService.AT_MOST_ONCE -> Unit
        }
        Unit
    }

    override suspend fun incomingMessagesToRedispatch(broker: MqttBroker): Collection<IncomingPublishRecord> =
        mutex.withLock {
            incomingPublishes[broker.identifier]
                ?.values
                ?.map { IncomingPublishRecord(it.packet, it.state) }
                ?: emptyList()
        }

    override suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    ) = mutex.withLock {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker.remove(packet.packetIdentifier)
        Unit
    }

    override suspend fun ackPubComplete(
        broker: MqttBroker,
        packet: IPublishComplete,
    ) = mutex.withLock {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker.remove(packet.packetIdentifier)
        Unit
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest =
        mutex.withLock {
            val packetId = getPacketId()
            // Inlined read of activeSubscriptions[broker] — the Mutex is non-reentrant, so
            // calling activeSubscriptions(broker) here would self-deadlock.
            val currentSubs = activeSubscriptions[broker.identifier] ?: emptyMap()
            val newSubscriptions = sub.subscriptions - currentSubs.values.toSet()
            val newSub = sub.controlPacketFactory.subscribe(newSubscriptions).copyWithNewPacketIdentifier(packetId)
            val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            clientMessagesForBroker[packetId] = newSub
            val activeSubscriptionsForBroker = activeSubscriptions.getOrPut(broker.identifier) { LinkedHashMap() }
            newSubscriptions.forEach {
                activeSubscriptionsForBroker[it.topicFilter] = it
            }
            newSub
        }

    override suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest? = mutex.withLock { clientMessages[broker.identifier]?.get(packetId) as? ISubscribeRequest }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease,
    ) = mutex.withLock {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker[incomingPubRecv.packetIdentifier] = pubRel
        Unit
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    ) = mutex.withLock {
        check(incomingPubRel.packetIdentifier == outPubComp.packetIdentifier)
        val serverMessagesForBroker = serverMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        serverMessagesForBroker[outPubComp.packetIdentifier] = outPubComp
        Unit
    }

    override suspend fun onPubCompWritten(
        broker: MqttBroker,
        outPubComp: IPublishComplete,
    ) = mutex.withLock {
        serverMessages[broker.identifier]?.remove(outPubComp.packetIdentifier)
        incomingPublishes[broker.identifier]?.remove(outPubComp.packetIdentifier)
        Unit
    }

    override suspend fun ackSub(
        broker: MqttBroker,
        subAck: ISubscribeAcknowledgement,
    ) = mutex.withLock {
        clientMessages[broker.identifier]?.remove(subAck.packetIdentifier)
        Unit
    }

    override suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    ) = mutex.withLock {
        val unsub =
            clientMessages[broker.identifier]?.remove(unsubAck.packetIdentifier) as? IUnsubscribeRequest ?: return@withLock
        unsub.topics.forEach { activeSubscriptions[broker.identifier]?.remove(it) }
    }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttBroker =
        mutex.withLock {
            val id = brokers.size
            val broker = MqttBroker(id, connectionOps, connectionRequest)
            brokers[id] = broker
            broker
        }

    override suspend fun allBrokers(): Collection<MqttBroker> = mutex.withLock { brokers.values.toList() }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? = mutex.withLock { brokers[identifier] }

    override suspend fun removeBroker(identifier: Int) =
        mutex.withLock {
            brokers.remove(identifier)
            Unit
        }

    // Used for debugging purposes
    override suspend fun isQueueClear(
        broker: MqttBroker,
        includeSubscriptions: Boolean,
    ): Boolean =
        mutex.withLock {
            serverMessages
                .filter { it.value.isEmpty() }
                .forEach { serverMessages.remove(it.key) }
            clientMessages
                .filter { it.value.isEmpty() }
                .forEach { clientMessages.remove(it.key) }
            incomingPublishes
                .filter { it.value.isEmpty() }
                .forEach { incomingPublishes.remove(it.key) }
            activeSubscriptions
                .filter { it.value.isEmpty() }
                .forEach { activeSubscriptions.remove(it.key) }

            val isClear =
                serverMessages.isEmpty() &&
                    clientMessages.isEmpty() &&
                    incomingPublishes.isEmpty() &&
                    if (includeSubscriptions) {
                        activeSubscriptions.isEmpty()
                    } else {
                        true
                    }
            if (!isClear) {
                if (serverMessages.isNotEmpty()) {
                    println(serverMessages.values.joinToString(prefix = "Q server: "))
                }
                if (clientMessages.isNotEmpty()) {
                    println(clientMessages.values.joinToString(prefix = "Q client: "))
                }
                if (incomingPublishes.isNotEmpty()) {
                    println(incomingPublishes.values.joinToString(prefix = "Q incoming: "))
                }
                if (includeSubscriptions && activeSubscriptions.isNotEmpty()) {
                    println(activeSubscriptions.values.joinToString(prefix = "Q sub: "))
                }
            }
            isClear
        }

    override suspend fun updatePublishState(
        broker: MqttBroker,
        packetId: Int,
        state: Int,
    ) {
        // In-memory persistence doesn't track state separately — the state is implicit
        // in which map (clientMessages vs serverMessages) holds the packet.
    }

    private fun getPacketId(): Int {
        nextPacketId++
        if (nextPacketId.toInt() == 0) {
            nextPacketId++
        }
        return nextPacketId.toInt()
    }
}
