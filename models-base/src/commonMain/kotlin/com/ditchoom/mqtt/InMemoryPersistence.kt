@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.mqtt

import com.ditchoom.mqtt.connection.MqttBroker
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
import com.ditchoom.mqtt.controlpacket.PacketId
import com.ditchoom.mqtt.controlpacket.PacketIdAllocation
import com.ditchoom.mqtt.controlpacket.PacketIdsExhaustedException
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lock-free: the whole store is one immutable [Store] snapshot swapped by compare-and-set, so
 * concurrent callers can never observe or produce a half-applied update. Packet identifiers are
 * allocated in the same transition that records the packet, skipping every identifier still held
 * by an unacknowledged client packet for that broker.
 */
class InMemoryPersistence : Persistence {
    private val store = AtomicReference(Store())

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean,
    ): Map<TopicFilter, ISubscription> = store.load().session(broker).subscriptions

    override suspend fun clearMessages(broker: MqttBroker) =
        update { it.withSession(broker) { s -> s.copy(clientMessages = emptyMap(), incoming = emptyMap()) } }

    override suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: PublishMessage,
    ): Int = allocate(broker) { id -> pub.maybeCopyWithNewPacketIdentifier(id.value) }.value

    override suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): PublishMessage? = store.load().session(broker).clientMessages[packetId] as? PublishMessage

    override suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int = allocate(broker) { id -> unsub.copyWithNewPacketIdentifier(id.value) }.value

    override suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest? = store.load().session(broker).clientMessages[packetId] as? IUnsubscribeRequest

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val session = store.load().session(broker)
        val client = session.clientMessages.values.map { (it as? PublishMessage)?.setDupFlagNewPubMessage() ?: it }
        return (client + session.serverMessages.values).sortedBy { it.packetIdentifier }
    }

    override suspend fun persistIncomingPublish(
        broker: MqttBroker,
        packet: PublishMessage,
    ) {
        if (packet.qualityOfService == QualityOfService.AT_MOST_ONCE) return
        update {
            it.withSession(broker) { s ->
                s.copy(
                    incoming =
                        s.incoming +
                            (packet.packetIdentifier to IncomingPublishRecord(packet, Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER)),
                )
            }
        }
    }

    override suspend fun incomingHandlerComplete(
        broker: MqttBroker,
        packetId: Int,
    ) = update {
        it.withSession(broker) { s ->
            val record = s.incoming[packetId] ?: return@withSession s
            when (record.packet.qualityOfService) {
                QualityOfService.AT_LEAST_ONCE -> s.copy(incoming = s.incoming - packetId)
                QualityOfService.EXACTLY_ONCE ->
                    s.copy(
                        incoming =
                            s.incoming +
                                (packetId to record.copy(state = Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT)),
                    )
                QualityOfService.AT_MOST_ONCE -> s
            }
        }
    }

    override suspend fun incomingMessagesToRedispatch(broker: MqttBroker): Collection<IncomingPublishRecord> =
        store
            .load()
            .session(broker)
            .incoming.values

    override suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    ) = update { it.withSession(broker) { s -> s.copy(clientMessages = s.clientMessages - packet.packetIdentifier) } }

    override suspend fun ackPubComplete(
        broker: MqttBroker,
        packet: IPublishComplete,
    ) = update { it.withSession(broker) { s -> s.copy(clientMessages = s.clientMessages - packet.packetIdentifier) } }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest =
        transact { current ->
            val session = current.session(broker)
            val id = current.nextFreeId(session)
            val newSubscriptions = sub.subscriptions - session.subscriptions.values.toSet()
            val newSub = sub.controlPacketFactory.subscribe(newSubscriptions).copyWithNewPacketIdentifier(id.value)
            val next =
                session.copy(
                    clientMessages = session.clientMessages + (id.value to newSub),
                    subscriptions = session.subscriptions + newSubscriptions.associateBy { it.topicFilter },
                )
            current.copy(lastPacketId = id).withSession(broker) { next } to newSub
        }

    override suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest? = store.load().session(broker).clientMessages[packetId] as? ISubscribeRequest

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease,
    ) = update { it.withSession(broker) { s -> s.copy(clientMessages = s.clientMessages + (incomingPubRecv.packetIdentifier to pubRel)) } }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    ) {
        check(incomingPubRel.packetIdentifier == outPubComp.packetIdentifier)
        update { it.withSession(broker) { s -> s.copy(serverMessages = s.serverMessages + (outPubComp.packetIdentifier to outPubComp)) } }
    }

    override suspend fun onPubCompWritten(
        broker: MqttBroker,
        outPubComp: IPublishComplete,
    ) = update {
        it.withSession(broker) { s ->
            s.copy(
                serverMessages = s.serverMessages - outPubComp.packetIdentifier,
                incoming = s.incoming - outPubComp.packetIdentifier,
            )
        }
    }

    override suspend fun ackSub(
        broker: MqttBroker,
        subAck: ISubscribeAcknowledgement,
    ) = update { it.withSession(broker) { s -> s.copy(clientMessages = s.clientMessages - subAck.packetIdentifier) } }

    override suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    ) = update {
        it.withSession(broker) { s ->
            val unsub = s.clientMessages[unsubAck.packetIdentifier] as? IUnsubscribeRequest ?: return@withSession s
            s.copy(
                clientMessages = s.clientMessages - unsubAck.packetIdentifier,
                subscriptions = s.subscriptions - unsub.topics.toSet(),
            )
        }
    }

    override suspend fun addBroker(connectionRequest: IConnectionRequest): MqttBroker =
        transact { current ->
            val broker = MqttBroker(current.brokers.size, connectionRequest)
            current.copy(brokers = current.brokers + (broker.identifier to broker)) to broker
        }

    override suspend fun allBrokers(): Collection<MqttBroker> = store.load().brokers.values

    override suspend fun brokerWithId(identifier: Int): MqttBroker? = store.load().brokers[identifier]

    override suspend fun removeBroker(identifier: Int) = update { it.copy(brokers = it.brokers - identifier) }

    override suspend fun isQueueClear(
        broker: MqttBroker,
        includeSubscriptions: Boolean,
    ): Boolean {
        val sessions = store.load().sessions.values
        val client = sessions.flatMap { it.clientMessages.values }
        val server = sessions.flatMap { it.serverMessages.values }
        val incoming = sessions.flatMap { it.incoming.values }
        val subscriptions = if (includeSubscriptions) sessions.flatMap { it.subscriptions.values } else emptyList()
        val isClear = client.isEmpty() && server.isEmpty() && incoming.isEmpty() && subscriptions.isEmpty()
        if (!isClear) {
            if (server.isNotEmpty()) println(server.joinToString(prefix = "Q server: "))
            if (client.isNotEmpty()) println(client.joinToString(prefix = "Q client: "))
            if (incoming.isNotEmpty()) println(incoming.joinToString(prefix = "Q incoming: "))
            if (subscriptions.isNotEmpty()) println(subscriptions.joinToString(prefix = "Q sub: "))
        }
        return isClear
    }

    override suspend fun updatePublishState(
        broker: MqttBroker,
        packetId: Int,
        state: Int,
    ) {
        // The state is implicit in which map (client vs server messages) holds the packet.
    }

    private fun allocate(
        broker: MqttBroker,
        record: (PacketId) -> ControlPacket,
    ): PacketId =
        transact { current ->
            val session = current.session(broker)
            val id = current.nextFreeId(session)
            current
                .copy(lastPacketId = id)
                .withSession(broker) { session.copy(clientMessages = session.clientMessages + (id.value to record(id))) } to id
        }

    private inline fun update(transform: (Store) -> Store) = transact { transform(it) to Unit }

    private inline fun <R> transact(transform: (Store) -> Pair<Store, R>): R {
        while (true) {
            val current = store.load()
            val (next, result) = transform(current)
            if (store.compareAndSet(current, next)) return result
        }
    }
}

private data class Store(
    val lastPacketId: PacketId = PacketId(PacketId.MAX),
    val brokers: Map<Int, MqttBroker> = emptyMap(),
    val sessions: Map<Int, BrokerSession> = emptyMap(),
) {
    fun session(broker: MqttBroker): BrokerSession = sessions[broker.identifier] ?: BrokerSession()

    inline fun withSession(
        broker: MqttBroker,
        transform: (BrokerSession) -> BrokerSession,
    ): Store = copy(sessions = sessions + (broker.identifier to transform(session(broker))))

    fun nextFreeId(session: BrokerSession): PacketId =
        when (val allocation = lastPacketId.nextFree { it.value in session.clientMessages }) {
            is PacketIdAllocation.Allocated -> allocation.id
            PacketIdAllocation.Exhausted -> throw PacketIdsExhaustedException()
        }
}

private data class BrokerSession(
    val clientMessages: Map<Int, ControlPacket> = emptyMap(),
    val serverMessages: Map<Int, ControlPacket> = emptyMap(),
    val incoming: Map<Int, IncomingPublishRecord> = emptyMap(),
    val subscriptions: Map<TopicFilter, ISubscription> = emptyMap(),
)
