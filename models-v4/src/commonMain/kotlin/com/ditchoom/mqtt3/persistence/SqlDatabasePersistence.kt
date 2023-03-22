package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver
import com.ditchoom.Mqtt4
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.wrap
import com.ditchoom.mqtt.Persistence
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
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.Subscription
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class SqlDatabasePersistence(driver: SqlDriver) : Persistence {
    private val packetIdMutex = Mutex()
    private val database = Mqtt4(driver)
    private val brokerQueries = database.brokerQueries
    private val connectionRequestQueries = database.connectionRequestQueries
    private val socketConnectionQueries = database.socketConnectionQueries
    private val pubQueries = database.publishMessageQueries
    private val qos2Messages = database.qoS2MessagesQueries
    private val subscriptionQueries = database.subscriptionQueries
    private val subQueries = database.subscriptionRequestQueries
    private val unsubQueries = database.unsubscribeRequestQueries
    private val dispatcher = defaultDispatcher(1, "mqtt.sql")

    override suspend fun ackPub(broker: MqttBroker, packet: IPublishAcknowledgment) = withContext(dispatcher) {
        pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, packet.packetIdentifier.toLong())
    }

    override suspend fun ackPubComplete(broker: MqttBroker, packet: IPublishComplete) = withContext(dispatcher) {
        qos2Messages.deleteQos2Message(broker.identifier.toLong(), 0L, packet.packetIdentifier.toLong())
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease
    ) = withContext(dispatcher) {
        qos2Messages.transaction {
            pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, incomingPubRecv.packetIdentifier.toLong())
            qos2Messages.insertQos2Message(
                broker.identifier.toLong(),
                0L,
                incomingPubRecv.packetIdentifier.toLong(),
                pubRel.controlPacketValue.toLong()
            )
        }
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete
    ) = withContext(dispatcher) {
        qos2Messages.updateQos2Message(
            outPubComp.controlPacketValue.toLong(),
            broker.identifier.toLong(),
            1L,
            incomingPubRel.packetIdentifier.toLong()
        )
    }

    override suspend fun ackSub(broker: MqttBroker, subAck: ISubscribeAcknowledgement) = withContext(dispatcher) {
        subQueries.deleteSubscribeRequest(broker.identifier.toLong(), subAck.packetIdentifier.toLong())
    }

    override suspend fun ackUnsub(broker: MqttBroker, unsubAck: IUnsubscribeAcknowledgment) = withContext(dispatcher) {
        subQueries.transaction {
            unsubQueries.deleteUnsubscribeRequest(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
            subscriptionQueries.deleteSubscription(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
        }
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean
    ): Map<Topic, ISubscription> {
        return withContext(dispatcher) {
            if (includePendingUnsub) {
                subscriptionQueries
                    .allSubscriptions(broker.identifier.toLong())
            } else {
                subscriptionQueries
                    .allSubscriptionsNotPendingUnsub(broker.identifier.toLong())
            }.executeAsList()
                .map { Subscription(Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter), it.qos.toQos()) }
                .associateBy { it.topicFilter }
        }
    }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker {
        val connect = connectionRequest as ConnectionRequest
        val brokerId = brokerQueries.transactionWithResult {
            brokerQueries.insertBroker()
            val brokerId = brokerQueries.lastRowId().executeAsOne()
            val willPayload = connect.payload.willPayload
            val willPayloadByteArray = willPayload?.readByteArray(willPayload.remaining())
            willPayload?.resetForRead()
            connectionRequestQueries.insertConnectionRequest(
                brokerId,
                connect.protocolName,
                connect.protocolVersion.toLong(),
                connect.variableHeader.willRetain.toLong(),
                connect.variableHeader.willQos.integerValue.toLong(),
                connect.variableHeader.willFlag.toLong(),
                connect.variableHeader.cleanSession.toLong(),
                connect.variableHeader.keepAliveSeconds.toLong(),
                connect.payload.clientId,
                connect.payload.willTopic?.toString(),
                willPayloadByteArray,
                connect.payload.userName,
                connect.payload.password
            )

            connectionOps.forEach {
                when (it) {
                    is MqttConnectionOptions.SocketConnection -> {
                        socketConnectionQueries.insertConnection(
                            brokerId,
                            "tcp",
                            it.host,
                            it.port.toLong(),
                            if (it.tls) 1.toLong() else 0.toLong(),
                            it.connectionTimeout.inWholeMilliseconds,
                            it.readTimeout.inWholeMilliseconds,
                            it.writeTimeout.inWholeMilliseconds,
                            null,
                            null
                        )
                    }

                    is MqttConnectionOptions.WebSocketConnectionOptions -> {
                        socketConnectionQueries.insertConnection(
                            brokerId,
                            "websocket",
                            it.host,
                            it.port.toLong(),
                            if (it.tls) 1.toLong() else 0.toLong(),
                            it.connectionTimeout.inWholeMilliseconds,
                            it.readTimeout.inWholeMilliseconds,
                            it.writeTimeout.inWholeMilliseconds,
                            it.websocketEndpoint,
                            it.protocols.joinToString()
                        )
                    }
                }
            }
            brokerId
        }
        return MqttBroker(brokerId.toInt(), connectionOps, connectionRequest)
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        return brokerQueries
            .allBrokers()
            .executeAsList()
            .map { broker ->
                val connectionRequestDatabaseRecord =
                    connectionRequestQueries.connectionRequestByBrokerId(broker.id).executeAsOne()
                val willPayload = if (connectionRequestDatabaseRecord.will_payload != null) {
                    PlatformBuffer.wrap(connectionRequestDatabaseRecord.will_payload, ByteOrder.BIG_ENDIAN)
                } else {
                    null
                }
                val connectionRequest = ConnectionRequest(
                    connectionRequestDatabaseRecord.client_id,
                    connectionRequestDatabaseRecord.keep_alive_seconds.toInt(),
                    connectionRequestDatabaseRecord.clean_session == 1L,
                    connectionRequestDatabaseRecord.username,
                    connectionRequestDatabaseRecord.password,
                    connectionRequestDatabaseRecord.will_topic,
                    willPayload,
                    connectionRequestDatabaseRecord.will_retain == 1L,
                    connectionRequestDatabaseRecord.will_qos.toQos(),
                    connectionRequestDatabaseRecord.protocol_name,
                    connectionRequestDatabaseRecord.protocol_level.toUByte()
                )
                val socketConnections = socketConnectionQueries.connectionsByBrokerId(broker.id)
                val connectionOps = socketConnections.executeAsList()
                    .map {
                        if (it.type == "websocket") {
                            MqttConnectionOptions.WebSocketConnectionOptions(
                                it.host,
                                it.port.toInt(),
                                it.tls == 1L,
                                it.connection_timeout_ms.milliseconds,
                                it.read_timeout_ms.milliseconds,
                                it.write_timeout_ms.milliseconds,
                                checkNotNull(it.websocket_endpoint),
                                if (checkNotNull(it.websocket_protocols).isNotEmpty()) {
                                    it.websocket_protocols.split(",")
                                } else {
                                    listOf()
                                },
                            )
                        } else {
                            MqttConnectionOptions.SocketConnection(
                                it.host,
                                it.port.toInt(),
                                it.tls == 1L,
                                it.connection_timeout_ms.milliseconds,
                                it.read_timeout_ms.milliseconds,
                                it.write_timeout_ms.milliseconds
                            )
                        }
                    }.toSet()
                MqttBroker(broker.id.toInt(), connectionOps, connectionRequest)
            }
    }

    override suspend fun clearMessages(broker: MqttBroker) = withContext(dispatcher) {
        qos2Messages.deleteAll(broker.identifier.toLong())
        pubQueries.deleteAll(broker.identifier.toLong())
        subQueries.deleteAll(broker.identifier.toLong())
        subscriptionQueries.deleteAll(broker.identifier.toLong())
        unsubQueries.deleteAll(broker.identifier.toLong())
    }

    override suspend fun incomingPublish(broker: MqttBroker, packet: IPublishMessage, replyMessage: ControlPacket) =
        withContext(dispatcher) {
            if (packet.qualityOfService != QualityOfService.EXACTLY_ONCE) {
                return@withContext
            }
            qos2Messages.insertQos2Message(
                broker.identifier.toLong(),
                1L,
                packet.packetIdentifier.toLong(),
                replyMessage.controlPacketValue.toLong()
            )
        }

    private fun Long.toQos(): QualityOfService {
        return when (this) {
            1L -> QualityOfService.AT_LEAST_ONCE
            2L -> QualityOfService.EXACTLY_ONCE
            else -> QualityOfService.AT_MOST_ONCE
        }
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val map = ArrayList<ControlPacket>()
        map += pubQueries.queuedPubMessages(broker.identifier.toLong()).executeAsList().map {
            val payload = if (it.payload != null) {
                PlatformBuffer.wrap(it.payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
            PublishMessage(
                PublishMessage.FixedHeader(true, it.qos.toQos(), it.retain == 1L),
                PublishMessage.VariableHeader(Topic.fromOrThrow(it.topic_name, Topic.Type.Name), it.packet_id.toInt()),
                payload
            )
        }
        map += qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().map {
            when (it.type) {
                5L -> PublishReceived(it.packet_id.toInt())
                6L -> PublishRelease(it.packet_id.toInt())
                7L -> PublishComplete(it.packet_id.toInt())
                else -> throw IllegalArgumentException("Unexpected type ${it.type}")
            }
        }

        map += subQueries.queuedSubMessages(broker.identifier.toLong()).executeAsList().map { subscribeRequest ->
            val subs = subscriptionQueries
                .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                .executeAsList().map {
                    Subscription(Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter), it.qos.toQos())
                }.toSet()
            SubscribeRequest(subscribeRequest.packet_id.toInt(), subs)
        }
        map += unsubQueries.queuedUnsubMessages(broker.identifier.toLong()).executeAsList()
            .mapNotNull { unsubscribeRequest ->
                val subscriptions = subscriptionQueries
                    .queuedUnsubscriptions(unsubscribeRequest.broker_id, unsubscribeRequest.packet_id)
                    .executeAsList().map {
                        Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter)
                    }.toSet()
                if (subscriptions.isNotEmpty()) {
                    UnsubscribeRequest(unsubscribeRequest.packet_id.toInt(), subscriptions)
                } else {
                    null
                }
            }
        return map.sortedBy { it.packetIdentifier }
    }

    override suspend fun onPubCompWritten(broker: MqttBroker, outPubComp: IPublishComplete) = withContext(dispatcher) {
        qos2Messages.deleteQos2Message(broker.identifier.toLong(), 1L, outPubComp.packetIdentifier.toLong())
    }

    override suspend fun removeBroker(identifier: Int) = withContext(dispatcher) {
        brokerQueries.deleteBroker(identifier.toLong())
    }

    override suspend fun writePubGetPacketId(broker: MqttBroker, pub: IPublishMessage): Int {
        if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            return NO_PACKET_ID
        }
        val brokerId = broker.identifier
        val p = pub as PublishMessage
        val payload = p.payload?.readByteArray(p.payload.remaining())
        p.payload?.resetForRead()
        val packetId = withContext(dispatcher) {
            packetIdMutex.withLock {
                brokerQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(brokerId.toLong()).executeAsOne().toLong()
                    brokerQueries.incrementPacketId(brokerId.toLong())
                    pubQueries.insertPublishMessage(
                        broker.identifier.toLong(),
                        0L,
                        if (p.fixed.dup) 1L else 0L,
                        p.fixed.qos.integerValue.toLong(),
                        if (p.fixed.retain) 1L else 0L,
                        p.topic.toString(),
                        packetId,
                        payload
                    )
                    packetId.toInt()
                }
            }
        }
        return packetId
    }

    override suspend fun getPubWithPacketId(broker: MqttBroker, packetId: Int): IPublishMessage? {
        val pub = pubQueries.messageWithId(broker.identifier.toLong(), 0L, packetId.toLong())
            .executeAsOneOrNull() ?: return null
        val payload = if (pub.payload != null) {
            PlatformBuffer.wrap(pub.payload, ByteOrder.BIG_ENDIAN)
        } else {
            null
        }
        return PublishMessage(
            PublishMessage.FixedHeader(pub.dup == 1L, pub.qos.toQos(), pub.retain == 1L),
            PublishMessage.VariableHeader(Topic.fromOrThrow(pub.topic_name, Topic.Type.Name), pub.packet_id.toInt()),
            payload
        )
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest
    ): ISubscribeRequest {
        val packetId = withContext(dispatcher) {
            packetIdMutex.withLock {
                subQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne().toLong()
                    brokerQueries.incrementPacketId(broker.identifier.toLong())
                    subQueries.insertSubscribeRequest(broker.identifier.toLong(), packetId.toLong())
                    sub.subscriptions.forEach {
                        subscriptionQueries.insertSubscription(
                            broker.identifier.toLong(),
                            packetId.toLong(),
                            it.topicFilter.toString(),
                            it.maximumQos.integerValue.toLong()
                        )
                    }
                    packetId
                }
            }
        }
        return sub.copyWithNewPacketIdentifier(packetId.toInt())
    }

    override suspend fun getSubWithPacketId(broker: MqttBroker, packetId: Int): ISubscribeRequest? {
        val subscribeRequest = subQueries.messageWithId(broker.identifier.toLong(), packetId.toLong())
            .executeAsOneOrNull() ?: return null
        val subs = subscriptionQueries
            .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
            .executeAsList().map {
                Subscription(Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter), it.qos.toQos())
            }.toSet()
        return SubscribeRequest(subscribeRequest.packet_id.toInt(), subs)
    }

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        return withContext(dispatcher) {
            packetIdMutex.withLock {
                unsubQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne().toLong()
                    brokerQueries.incrementPacketId(broker.identifier.toLong())
                    unsubQueries.insertUnsubscribeRequest(broker.identifier.toLong(), packetId.toLong())
                    unsub.topics.forEach {
                        subscriptionQueries.addUnsubscriptionPacketId(
                            packetId.toLong(),
                            broker.identifier.toLong(),
                            it.toString()
                        )
                    }
                    packetId.toInt()
                }
            }
        }
    }

    override suspend fun getUnsubWithPacketId(broker: MqttBroker, packetId: Int): IUnsubscribeRequest? {
        val unsubscribeRequest = unsubQueries.messageWithId(broker.identifier.toLong(), packetId.toLong())
            .executeAsOneOrNull() ?: return null
        val subscriptions = subscriptionQueries
            .queuedUnsubscriptions(unsubscribeRequest.broker_id, unsubscribeRequest.packet_id)
            .executeAsList().map {
                Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter)
            }.toSet()
        return if (subscriptions.isNotEmpty()) {
            UnsubscribeRequest(unsubscribeRequest.packet_id.toInt(), subscriptions)
        } else {
            null
        }
    }

    override suspend fun isQueueClear(broker: MqttBroker, includeSubscriptions: Boolean): Boolean {
        val msgCount = pubQueries.publishMessageCount(broker.identifier.toLong()).executeAsOne()
        if (msgCount > 0) {
            println(pubQueries.allMessages(broker.identifier.toLong()).executeAsList().joinToString())
        }
        val qos2Count = qos2Messages.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        if (qos2Count > 0) {
            println(qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().joinToString())
        }
        val subscriptionCount = if (includeSubscriptions) {
            subscriptionQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        } else {
            0
        }
        val subCount = subQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        val unsubCount = unsubQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        return msgCount == 0L && qos2Count == 0L && subscriptionCount == 0L && subCount == 0L && unsubCount == 0L
    }
}

fun Boolean.toLong(): Long {
    return if (this) {
        1L
    } else {
        0L
    }
}
