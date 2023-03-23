package com.ditchoom.mqtt

import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic

class InMemoryPersistence : Persistence {
    private var nextPacketId = 0.toUShort()

    private val activeSubscriptions = HashMap<Int, MutableMap<Topic, ISubscription>>()

    // client messages
    private val clientMessages = HashMap<Int, MutableMap<Int, ControlPacket>>()

    // server messages
    private val serverMessages = HashMap<Int, MutableMap<Int, ControlPacket>>()

    private val brokers = mutableMapOf<Int, MqttBroker>()
    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean
    ): Map<Topic, ISubscription> =
        activeSubscriptions[broker.identifier] ?: emptyMap()

    override suspend fun clearMessages(broker: MqttBroker) {
        clientMessages.clear()
    }

    override suspend fun writePubGetPacketId(broker: MqttBroker, pub: IPublishMessage): Int {
        val packetId = getPacketId()
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker[packetId] = pub
            .maybeCopyWithNewPacketIdentifier(packetId)
        return packetId
    }

    override suspend fun getPubWithPacketId(broker: MqttBroker, packetId: Int): IPublishMessage? {
        return clientMessages[broker.identifier]?.get(packetId) as? IPublishMessage
    }

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        val packetId = getPacketId()
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker[packetId] = unsub.copyWithNewPacketIdentifier(packetId)
        return packetId
    }

    override suspend fun getUnsubWithPacketId(broker: MqttBroker, packetId: Int): IUnsubscribeRequest? {
        return clientMessages[broker.identifier]?.get(packetId) as? IUnsubscribeRequest
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        val clientMap = LinkedHashMap<Int, ControlPacket>()
        clientMessagesForBroker.forEach { (key, value) ->
            clientMap[key] = if (value is IPublishMessage) {
                value.setDupFlagNewPubMessage()
            } else {
                value
            }
        }
        val serverMessagesForBroker = serverMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        return (clientMap.values + serverMessagesForBroker.values).sortedBy { it.packetIdentifier }
    }

    override suspend fun incomingPublish(broker: MqttBroker, packet: IPublishMessage, replyMessage: ControlPacket) {
        if (packet.qualityOfService == QualityOfService.EXACTLY_ONCE) {
            val serverMessagesForBroker = serverMessages.getOrPut(broker.identifier) { LinkedHashMap() }
            serverMessagesForBroker[packet.packetIdentifier] = replyMessage
        }
    }

    override suspend fun ackPub(broker: MqttBroker, packet: IPublishAcknowledgment) {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker.remove(packet.packetIdentifier)
    }

    override suspend fun ackPubComplete(broker: MqttBroker, packet: IPublishComplete) {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker.remove(packet.packetIdentifier)
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest
    ): ISubscribeRequest {
        val packetId = getPacketId()
        val newSubscriptions = sub.subscriptions - activeSubscriptions(broker).values.toSet()
        val newSub = sub.controlPacketFactory.subscribe(newSubscriptions).copyWithNewPacketIdentifier(packetId)
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker[packetId] = newSub
        val activeSubscriptionsForBroker = activeSubscriptions.getOrPut(broker.identifier) { LinkedHashMap() }
        newSubscriptions.forEach {
            activeSubscriptionsForBroker[it.topicFilter] = it
        }
        return newSub
    }

    override suspend fun getSubWithPacketId(broker: MqttBroker, packetId: Int): ISubscribeRequest? {
        return clientMessages[broker.identifier]?.get(packetId) as? ISubscribeRequest
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease
    ) {
        val clientMessagesForBroker = clientMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        clientMessagesForBroker[incomingPubRecv.packetIdentifier] = pubRel
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete
    ) {
        check(incomingPubRel.packetIdentifier == outPubComp.packetIdentifier)
        val serverMessagesForBroker = serverMessages.getOrPut(broker.identifier) { LinkedHashMap() }
        serverMessagesForBroker[outPubComp.packetIdentifier] = outPubComp
    }

    override suspend fun onPubCompWritten(broker: MqttBroker, outPubComp: IPublishComplete) {
        serverMessages[broker.identifier]?.remove(outPubComp.packetIdentifier)
    }

    override suspend fun ackSub(broker: MqttBroker, subAck: ISubscribeAcknowledgement) {
        clientMessages[broker.identifier]?.remove(subAck.packetIdentifier)
    }

    override suspend fun ackUnsub(broker: MqttBroker, unsubAck: IUnsubscribeAcknowledgment) {
        val unsub =
            clientMessages[broker.identifier]?.remove(unsubAck.packetIdentifier) as? IUnsubscribeRequest ?: return
        unsub.topics.forEach { activeSubscriptions[broker.identifier]?.remove(it) }
    }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker {
        val id = brokers.size
        val broker = MqttBroker(id, connectionOps, connectionRequest)
        brokers[id] = broker
        return broker
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        println("all brokers in memory persistence")
        return brokers.values
    }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? {
        return brokers[identifier]
    }

    override suspend fun removeBroker(identifier: Int) {
        brokers.remove(identifier)
    }

    // Used for debugging purposes
    override suspend fun isQueueClear(broker: MqttBroker, includeSubscriptions: Boolean): Boolean {
        serverMessages
            .filter { it.value.isEmpty() }
            .forEach { serverMessages.remove(it.key) }
        clientMessages
            .filter { it.value.isEmpty() }
            .forEach { clientMessages.remove(it.key) }
        activeSubscriptions
            .filter { it.value.isEmpty() }
            .forEach { activeSubscriptions.remove(it.key) }

        val isClear = serverMessages.isEmpty() && clientMessages.isEmpty() && if (includeSubscriptions) {
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
            if (includeSubscriptions && activeSubscriptions.isNotEmpty()) {
                println(activeSubscriptions.values.joinToString(prefix = "Q sub: "))
            }
        }
        return isClear
    }

    private fun getPacketId(): Int {
        nextPacketId++
        if (nextPacketId.toInt() == 0) {
            nextPacketId++
        }
        return nextPacketId.toInt()
    }
}
