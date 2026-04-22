package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver
import com.ditchoom.Mqtt4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
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
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.payloadAsByteArrayOrNull
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.Subscription
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class SqlDatabasePersistence(
    driver: SqlDriver,
) : Persistence {
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

    override suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    ) {
        withContext(dispatcher) {
            pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, packet.packetIdentifier.toLong())
        }
    }

    override suspend fun ackPubComplete(
        broker: MqttBroker,
        packet: IPublishComplete,
    ) {
        withContext(dispatcher) {
            qos2Messages.deleteQos2Message(broker.identifier.toLong(), 0L, packet.packetIdentifier.toLong())
        }
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease,
    ) = withContext(dispatcher) {
        qos2Messages.transaction {
            pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, incomingPubRecv.packetIdentifier.toLong())
            qos2Messages.insertQos2Message(
                broker.identifier.toLong(),
                0L,
                incomingPubRecv.packetIdentifier.toLong(),
                pubRel.controlPacketValue.toLong(),
            )
        }
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    ) {
        withContext(dispatcher) {
            qos2Messages.updateQos2Message(
                outPubComp.controlPacketValue.toLong(),
                broker.identifier.toLong(),
                1L,
                incomingPubRel.packetIdentifier.toLong(),
            )
        }
    }

    override suspend fun ackSub(
        broker: MqttBroker,
        subAck: ISubscribeAcknowledgement,
    ) {
        withContext(dispatcher) {
            subQueries.deleteSubscribeRequest(broker.identifier.toLong(), subAck.packetIdentifier.toLong())
        }
    }

    override suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    ) = withContext(dispatcher) {
        unsubQueries.transaction {
            unsubQueries.deleteUnsubscribeRequest(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
            subscriptionQueries.deleteSubscription(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
        }
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean,
    ): Map<TopicFilter, ISubscription> =
        withContext(dispatcher) {
            if (includePendingUnsub) {
                subscriptionQueries
                    .allSubscriptions(broker.identifier.toLong())
            } else {
                subscriptionQueries
                    .allSubscriptionsNotPendingUnsub(broker.identifier.toLong())
            }.executeAsList()
                .map { Subscription(TopicFilter.fromOrThrow(it.topic_filter), it.qos.toQos()) }
                .associateBy { it.topicFilter }
        }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttBroker {
        val connect = connectionRequest as ConnectionRequest
        val brokerId =
            brokerQueries.transactionWithResult {
                brokerQueries.insertBroker()
                val brokerId = brokerQueries.lastRowId().executeAsOne()
                val willPayload = connect.payload.willPayload
                // SQLDelight BLOB binding takes ByteArray at the JDBC / native
                // SQLite driver boundary. True zero-copy needs a custom
                // ColumnAdapter (Phase 4 architecture work).
                @Suppress("NoByteArrayInProd") // SQLDelight BLOB boundary
                val willPayloadByteArray = willPayload?.readByteArray(willPayload.remaining())
                willPayload?.resetForRead()
                connectionRequestQueries.insertConnectionRequest(
                    brokerId,
                    connect.protocolName,
                    connect.protocolVersion.toLong(),
                    connect.variableHeader.willRetain.toLong(),
                    connect.variableHeader.willQos.integerValue
                        .toLong(),
                    connect.variableHeader.willFlag.toLong(),
                    connect.variableHeader.cleanSession.toLong(),
                    connect.variableHeader.keepAliveSeconds.toLong(),
                    connect.payload.clientId,
                    connect.payload.willTopic?.toString(),
                    willPayloadByteArray,
                    connect.payload.userName,
                    connect.payload.password,
                )

                connectionOps.forEach {
                    when (it) {
                        is MqttConnectionOptions.SocketConnection -> {
                            socketConnectionQueries.insertConnection(
                                brokerId,
                                "tcp",
                                it.host,
                                it.port.toLong(),
                                if (it.tlsEnabled) 1L else 0L,
                                if (it.tlsVerifyCerts) 1L else 0L,
                                if (it.tlsVerifyHostname) 1L else 0L,
                                if (it.tlsAllowExpired) 1L else 0L,
                                if (it.tlsAllowSelfSigned) 1L else 0L,
                                it.connectionTimeout.inWholeMilliseconds,
                                it.readTimeout.inWholeMilliseconds,
                                it.writeTimeout.inWholeMilliseconds,
                                null,
                                null,
                            )
                        }

                        is MqttConnectionOptions.WebSocketConnectionOptions -> {
                            socketConnectionQueries.insertConnection(
                                brokerId,
                                "websocket",
                                it.host,
                                it.port.toLong(),
                                if (it.tlsEnabled) 1L else 0L,
                                if (it.tlsVerifyCerts) 1L else 0L,
                                if (it.tlsVerifyHostname) 1L else 0L,
                                if (it.tlsAllowExpired) 1L else 0L,
                                if (it.tlsAllowSelfSigned) 1L else 0L,
                                it.connectionTimeout.inWholeMilliseconds,
                                it.readTimeout.inWholeMilliseconds,
                                it.writeTimeout.inWholeMilliseconds,
                                it.websocketEndpoint,
                                it.protocols.joinToString(),
                            )
                        }
                    }
                }
                brokerId
            }
        return MqttBroker(brokerId.toInt(), connectionOps, connectionRequest)
    }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? =
        socketConnectionQueries.transactionWithResult {
            getBrokerById(identifier.toLong())
        }

    private fun getBrokerById(id: Long): MqttBroker? {
        val socketConnections = socketConnectionQueries.connectionsByBrokerId(id)
        val connectionRequestDatabaseRecord =
            connectionRequestQueries.connectionRequestByBrokerId(id).executeAsOneOrNull() ?: return null
        val willPayload =
            if (connectionRequestDatabaseRecord.will_payload != null) {
                BufferFactory.Default.wrap(connectionRequestDatabaseRecord.will_payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
        val willTopic = connectionRequestDatabaseRecord.will_topic
        val willConfig =
            if (willTopic != null && willPayload != null) {
                WillConfig.Enabled(
                    TopicName.fromOrThrow(willTopic),
                    willPayload,
                    connectionRequestDatabaseRecord.will_qos.toQos(),
                    connectionRequestDatabaseRecord.will_retain == 1L,
                )
            } else {
                WillConfig.Disabled
            }
        val connectionRequest =
            ConnectionRequest(
                connectionRequestDatabaseRecord.client_id,
                connectionRequestDatabaseRecord.keep_alive_seconds.toInt(),
                connectionRequestDatabaseRecord.clean_session == 1L,
                connectionRequestDatabaseRecord.username,
                connectionRequestDatabaseRecord.password,
                willConfig,
                connectionRequestDatabaseRecord.protocol_name,
                connectionRequestDatabaseRecord.protocol_level.toUByte(),
            )
        val connectionOps =
            socketConnections
                .executeAsList()
                .map {
                    if (it.type == "websocket") {
                        MqttConnectionOptions.WebSocketConnectionOptions(
                            it.host,
                            it.port.toInt(),
                            tlsEnabled = it.tls_enabled == 1L,
                            tlsVerifyCerts = it.tls_verify_certs == 1L,
                            tlsVerifyHostname = it.tls_verify_hostname == 1L,
                            tlsAllowExpired = it.tls_allow_expired == 1L,
                            tlsAllowSelfSigned = it.tls_allow_self_signed == 1L,
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
                            tlsEnabled = it.tls_enabled == 1L,
                            tlsVerifyCerts = it.tls_verify_certs == 1L,
                            tlsVerifyHostname = it.tls_verify_hostname == 1L,
                            tlsAllowExpired = it.tls_allow_expired == 1L,
                            tlsAllowSelfSigned = it.tls_allow_self_signed == 1L,
                            it.connection_timeout_ms.milliseconds,
                            it.read_timeout_ms.milliseconds,
                            it.write_timeout_ms.milliseconds,
                        )
                    }
                }.toSet()
        if (connectionOps.isEmpty()) {
            return null
        }
        return MqttBroker(id.toInt(), connectionOps, connectionRequest)
    }

    override suspend fun allBrokers(): Collection<MqttBroker> =
        brokerQueries
            .allBrokers()
            .executeAsList()
            .mapNotNull { broker ->
                getBrokerById(broker.id)
            }

    override suspend fun clearMessages(broker: MqttBroker) {
        withContext(dispatcher) {
            qos2Messages.deleteAll(broker.identifier.toLong())
            pubQueries.deleteAll(broker.identifier.toLong())
            subQueries.deleteAll(broker.identifier.toLong())
            subscriptionQueries.deleteAll(broker.identifier.toLong())
            unsubQueries.deleteAll(broker.identifier.toLong())
        }
    }

    override suspend fun persistIncomingPublish(
        broker: MqttBroker,
        packet: PublishMessage,
    ) = withContext(dispatcher) {
        if (packet.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            return@withContext
        }
        val payload = packet.payloadAsByteArrayOrNull()
        pubQueries.insertPublishMessage(
            broker.identifier.toLong(),
            1L,
            if (packet.dup) 1L else 0L,
            packet.qualityOfService.integerValue
                .toLong(),
            if (packet.retain) 1L else 0L,
            packet.topic.toString(),
            packet.packetIdentifier.toLong(),
            payload,
        )
    }

    override suspend fun incomingHandlerComplete(
        broker: MqttBroker,
        packetId: Int,
    ) = withContext(dispatcher) {
        val row =
            pubQueries
                .messageWithId(broker.identifier.toLong(), 1L, packetId.toLong())
                .executeAsOneOrNull() ?: return@withContext
        when (row.qos.toQos()) {
            QualityOfService.AT_LEAST_ONCE ->
                pubQueries.deletePublishMessage(broker.identifier.toLong(), 1L, packetId.toLong())
            QualityOfService.EXACTLY_ONCE ->
                pubQueries.updateState(
                    Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT.toLong(),
                    broker.identifier.toLong(),
                    1L,
                    packetId.toLong(),
                )
            QualityOfService.AT_MOST_ONCE -> Unit
        }
    }

    override suspend fun incomingMessagesToRedispatch(broker: MqttBroker): Collection<com.ditchoom.mqtt.IncomingPublishRecord> =
        withContext(dispatcher) {
            pubQueries
                .queuedIncomingPubMessages(broker.identifier.toLong())
                .executeAsList()
                .map { row ->
                    val payload =
                        if (row.payload != null) {
                            BufferFactory.Default.wrap(row.payload, ByteOrder.BIG_ENDIAN)
                        } else {
                            null
                        }
                    val pub =
                        PublishMessageV4.ofRaw(
                            topic = TopicName.fromOrThrow(row.topic_name),
                            qos = row.qos.toQos(),
                            payload = payload,
                            dup = row.dup == 1L,
                            retain = row.retain == 1L,
                            packetIdentifier = row.packet_id.toInt(),
                        )
                    com.ditchoom.mqtt.IncomingPublishRecord(pub, row.state.toInt())
                }
        }

    private fun Long.toQos(): QualityOfService =
        when (this) {
            1L -> QualityOfService.AT_LEAST_ONCE
            2L -> QualityOfService.EXACTLY_ONCE
            else -> QualityOfService.AT_MOST_ONCE
        }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val map = ArrayList<ControlPacket>()
        map +=
            pubQueries.queuedPubMessages(broker.identifier.toLong()).executeAsList().map {
                val payload =
                    if (it.payload != null) {
                        BufferFactory.Default.wrap(it.payload, ByteOrder.BIG_ENDIAN)
                    } else {
                        null
                    }
                PublishMessageV4.ofRaw(
                    topic = TopicName.fromOrThrow(it.topic_name),
                    qos = it.qos.toQos(),
                    payload = payload,
                    dup = true,
                    retain = it.retain == 1L,
                    packetIdentifier = it.packet_id.toInt(),
                )
            }
        map +=
            qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().map {
                when (it.type) {
                    5L -> PublishReceived(it.packet_id.toUShort())
                    6L -> PublishRelease(it.packet_id.toUShort())
                    7L -> PublishComplete(it.packet_id.toUShort())
                    else -> throw IllegalArgumentException("Unexpected type ${it.type}")
                }
            }

        map +=
            subQueries.queuedSubMessages(broker.identifier.toLong()).executeAsList().map { subscribeRequest ->
                val subs =
                    subscriptionQueries
                        .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                        .executeAsList()
                        .map {
                            Subscription(TopicFilter.fromOrThrow(it.topic_filter), it.qos.toQos())
                        }.toSet()
                SubscribeRequest(subscribeRequest.packet_id.toInt(), subs)
            }
        map +=
            unsubQueries
                .queuedUnsubMessages(broker.identifier.toLong())
                .executeAsList()
                .mapNotNull { unsubscribeRequest ->
                    val subscriptions =
                        subscriptionQueries
                            .queuedUnsubscriptions(unsubscribeRequest.broker_id, unsubscribeRequest.packet_id)
                            .executeAsList()
                            .map {
                                TopicFilter.fromOrThrow(it.topic_filter)
                            }.toSet()
                    if (subscriptions.isNotEmpty()) {
                        UnsubscribeRequest(unsubscribeRequest.packet_id.toInt(), subscriptions)
                    } else {
                        null
                    }
                }
        return map.sortedBy { it.packetIdentifier }
    }

    override suspend fun onPubCompWritten(
        broker: MqttBroker,
        outPubComp: IPublishComplete,
    ) {
        withContext(dispatcher) {
            // incoming QoS 2 row lives on PublishMessage (incoming=1); delete both tables for safety
            pubQueries.deletePublishMessage(broker.identifier.toLong(), 1L, outPubComp.packetIdentifier.toLong())
            qos2Messages.deleteQos2Message(broker.identifier.toLong(), 1L, outPubComp.packetIdentifier.toLong())
        }
    }

    override suspend fun removeBroker(identifier: Int) {
        withContext(dispatcher) {
            brokerQueries.deleteBroker(identifier.toLong())
        }
    }

    override suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: PublishMessage,
    ): Int {
        if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            return NO_PACKET_ID
        }
        val brokerId = broker.identifier
        val payload = pub.payloadAsByteArrayOrNull()
        val packetId =
            withContext(dispatcher) {
                packetIdMutex.withLock {
                    brokerQueries.transactionWithResult {
                        val packetId = brokerQueries.nextPacketId(brokerId.toLong()).executeAsOne().toLong()
                        brokerQueries.incrementPacketId(brokerId.toLong())
                        pubQueries.insertPublishMessage(
                            broker.identifier.toLong(),
                            0L,
                            if (pub.dup) 1L else 0L,
                            pub.qualityOfService.integerValue
                                .toLong(),
                            if (pub.retain) 1L else 0L,
                            pub.topic.toString(),
                            packetId,
                            payload,
                        )
                        packetId.toInt()
                    }
                }
            }
        return packetId
    }

    override suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): PublishMessage? {
        val pub =
            pubQueries
                .messageWithId(broker.identifier.toLong(), 0L, packetId.toLong())
                .executeAsOneOrNull() ?: return null
        val payload =
            if (pub.payload != null) {
                BufferFactory.Default.wrap(pub.payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
        return PublishMessageV4.ofRaw(
            topic = TopicName.fromOrThrow(pub.topic_name),
            qos = pub.qos.toQos(),
            payload = payload,
            dup = pub.dup == 1L,
            retain = pub.retain == 1L,
            packetIdentifier = pub.packet_id.toInt(),
        )
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest {
        val packetId =
            withContext(dispatcher) {
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
                                it.maximumQos.integerValue.toLong(),
                            )
                        }
                        packetId
                    }
                }
            }
        return sub.copyWithNewPacketIdentifier(packetId.toInt())
    }

    override suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest? {
        val subscribeRequest =
            subQueries
                .messageWithId(broker.identifier.toLong(), packetId.toLong())
                .executeAsOneOrNull() ?: return null
        val subs =
            subscriptionQueries
                .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                .executeAsList()
                .map {
                    Subscription(TopicFilter.fromOrThrow(it.topic_filter), it.qos.toQos())
                }.toSet()
        return SubscribeRequest(subscribeRequest.packet_id.toInt(), subs)
    }

    override suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int =
        withContext(dispatcher) {
            packetIdMutex.withLock {
                unsubQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne().toLong()
                    brokerQueries.incrementPacketId(broker.identifier.toLong())
                    unsubQueries.insertUnsubscribeRequest(broker.identifier.toLong(), packetId.toLong())
                    unsub.topics.forEach {
                        subscriptionQueries.addUnsubscriptionPacketId(
                            packetId.toLong(),
                            broker.identifier.toLong(),
                            it.toString(),
                        )
                    }
                    packetId.toInt()
                }
            }
        }

    override suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest? {
        val unsubscribeRequest =
            unsubQueries
                .messageWithId(broker.identifier.toLong(), packetId.toLong())
                .executeAsOneOrNull() ?: return null
        val subscriptions =
            subscriptionQueries
                .queuedUnsubscriptions(unsubscribeRequest.broker_id, unsubscribeRequest.packet_id)
                .executeAsList()
                .map {
                    TopicFilter.fromOrThrow(it.topic_filter)
                }.toSet()
        return if (subscriptions.isNotEmpty()) {
            UnsubscribeRequest(unsubscribeRequest.packet_id.toInt(), subscriptions)
        } else {
            null
        }
    }

    override suspend fun isQueueClear(
        broker: MqttBroker,
        includeSubscriptions: Boolean,
    ): Boolean {
        val msgCount = pubQueries.publishMessageCount(broker.identifier.toLong()).executeAsOne()
        if (msgCount > 0) {
            println(pubQueries.allMessages(broker.identifier.toLong()).executeAsList().joinToString())
        }
        val qos2Count = qos2Messages.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        if (qos2Count > 0) {
            println(qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().joinToString())
        }
        val subscriptionCount =
            if (includeSubscriptions) {
                subscriptionQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
            } else {
                0
            }
        val subCount = subQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        val unsubCount = unsubQueries.queuedMessageCount(broker.identifier.toLong()).executeAsOne()
        return msgCount == 0L && qos2Count == 0L && subscriptionCount == 0L && subCount == 0L && unsubCount == 0L
    }

    override suspend fun updatePublishState(
        broker: MqttBroker,
        packetId: Int,
        state: Int,
    ) {
        pubQueries.updateState(
            state = state.toLong(),
            brokerId = broker.identifier.toLong(),
            incoming = 0, // outbound only
            packetId = packetId.toLong(),
        )
    }
}

fun Boolean.toLong(): Long =
    if (this) {
        1L
    } else {
        0L
    }
