package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver
import com.ditchoom.Mqtt5
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
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.payloadAsByteArrayOrNull
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.AckProperties
import com.ditchoom.mqtt5.controlpacket.AckVariableHeader
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishMessageV5
import com.ditchoom.mqtt5.controlpacket.PublishReceived
import com.ditchoom.mqtt5.controlpacket.PublishRelease
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class SqlDatabasePersistence(
    driver: SqlDriver,
) : Persistence {
    private val packetIdMutex = Mutex()
    private val database = Mqtt5(driver)
    private val brokerQueries = database.brokerQueries
    private val connectionRequestQueries = database.connectionRequestQueries
    private val socketConnectionQueries = database.socketConnectionQueries
    private val pubQueries = database.publishMessageQueries
    private val propertyQueries = database.userPropertyQueries
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
    ) {
        withContext(dispatcher) {
            val publishRelease = pubRel as PublishRelease
            qos2Messages.transaction {
                pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, incomingPubRecv.packetIdentifier.toLong())
                qos2Messages.insertQos2Message(
                    broker.identifier.toLong(),
                    0L,
                    incomingPubRecv.packetIdentifier.toLong(),
                    publishRelease.variable.reasonCode.byte
                        .toLong(),
                    publishRelease.variable.properties.reasonString,
                    publishRelease.controlPacketValue.toLong(),
                )
                for ((key, value) in publishRelease.variable.properties.userProperty) {
                    propertyQueries.addProp(
                        broker.identifier.toLong(),
                        0L,
                        incomingPubRecv.packetIdentifier.toLong(),
                        key,
                        value,
                    )
                }
            }
        }
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    ) {
        withContext(dispatcher) {
            qos2Messages.transaction {
                qos2Messages.updateQos2Message(
                    outPubComp.controlPacketValue.toLong(),
                    broker.identifier.toLong(),
                    1L,
                    incomingPubRel.packetIdentifier.toLong(),
                )
                val userProperty = (outPubComp as PublishComplete).variable.properties.userProperty
                if (userProperty.isNotEmpty()) {
                    for ((key, value) in userProperty) {
                        propertyQueries.addProp(
                            broker.identifier.toLong(),
                            1L,
                            outPubComp.packetIdentifier.toLong(),
                            key,
                            value,
                        )
                    }
                }
            }
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
    ) {
        withContext(dispatcher) {
            subQueries.transaction {
                unsubQueries.deleteUnsubscribeRequest(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
                subscriptionQueries.deleteSubscription(broker.identifier.toLong(), unsubAck.packetIdentifier.toLong())
            }
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
                // SQLite driver boundary — the three reads below materialise
                // Will payload / auth data / correlation data for that call.
                // A true zero-copy path needs a custom ColumnAdapter + per-
                // platform driver plumbing (deferred to Phase 4).
                @Suppress("NoByteArrayInProd")
                val willPayloadByteArray = willPayload?.readByteArray(willPayload.remaining())
                willPayload?.resetForRead()
                val authPayload =
                    connect.variableHeader.properties.authentication
                        ?.data
                @Suppress("NoByteArrayInProd") // SQLDelight BLOB boundary
                val authData = authPayload?.let { it.readByteArray(it.remaining()) }
                authPayload?.resetForRead()
                val correlationData = connect.payload.willProperties?.correlationData
                @Suppress("NoByteArrayInProd") // SQLDelight BLOB boundary
                val willPropsCorrelationData = correlationData?.let { it.readByteArray(it.remaining()) }
                correlationData?.resetForRead()
                connectionRequestQueries.insertConnectionRequest(
                    brokerId,
                    connect.protocolName,
                    connect.protocolVersion.toLong(),
                    connect.variableHeader.willRetain.toLong(),
                    connect.variableHeader.willQos.integerValue
                        .toLong(),
                    connect.variableHeader.willFlag.toLong(),
                    connect.variableHeader.cleanStart.toLong(),
                    connect.variableHeader.keepAliveSeconds.toLong(),
                    connect.variableHeader.properties.sessionExpiryIntervalSeconds
                        ?.toLong(),
                    connect.variableHeader.properties.receiveMaximum
                        ?.toLong(),
                    connect.variableHeader.properties.maximumPacketSize
                        ?.toLong(),
                    connect.variableHeader.properties.topicAliasMaximum
                        ?.toLong(),
                    connect.variableHeader.properties.requestResponseInformation
                        ?.toLong(),
                    connect.variableHeader.properties.requestProblemInformation
                        ?.toLong(),
                    connect.variableHeader.properties.authentication
                        ?.method,
                    authData,
                    connect.payload.clientId,
                    (connect.payload.willProperties != null).toLong(),
                    connect.payload.willTopic?.toString(),
                    willPayloadByteArray,
                    connect.payload.userName,
                    connect.payload.password,
                    connect.payload.willProperties?.willDelayIntervalSeconds ?: 0L,
                    connect.payload.willProperties
                        ?.payloadFormatIndicator
                        ?.toLong(),
                    connect.payload.willProperties?.messageExpiryIntervalSeconds,
                    connect.payload.willProperties?.contentType,
                    connect.payload.willProperties
                        ?.responseTopic
                        ?.toString(),
                    willPropsCorrelationData,
                )
                val userProps = connect.variableHeader.properties.userProperty
                for ((key, value) in userProps) {
                    propertyQueries.addProp(brokerId, 0, -1, key, value)
                }
                val willUserProps = connect.payload.willProperties?.userProperty
                if (willUserProps != null) {
                    for ((key, value) in willUserProps) {
                        propertyQueries.addProp(brokerId, 0, -2, key, value)
                    }
                }
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
        return MqttBroker(brokerId.toInt(), connectionOps.toSet(), connectionRequest)
    }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? =
        connectionRequestQueries.transactionWithResult {
            getBrokerById(identifier.toLong())
        }

    private fun getBrokerById(id: Long): MqttBroker? {
        val connectionRequestDatabaseRecord =
            connectionRequestQueries.connectionRequestByBrokerId(id).executeAsOneOrNull() ?: return null
        val willPayload =
            if (connectionRequestDatabaseRecord.will_payload != null) {
                BufferFactory.Default.wrap(connectionRequestDatabaseRecord.will_payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
        val auth =
            if (connectionRequestDatabaseRecord.authentication_method != null &&
                connectionRequestDatabaseRecord.authentication_data != null
            ) {
                Authentication(
                    connectionRequestDatabaseRecord.authentication_method,
                    BufferFactory.Default.wrap(connectionRequestDatabaseRecord.authentication_data),
                )
            } else {
                null
            }
        val userProps =
            propertyQueries
                .allProps(id, 0L, -1) { k, v ->
                    Pair(k, v)
                }.executeAsList()
        val willUserProps =
            propertyQueries
                .allProps(id, 0L, -2) { k, v ->
                    Pair(k, v)
                }.executeAsList()
        val variable =
            ConnectionRequest.VariableHeader(
                connectionRequestDatabaseRecord.protocol_name,
                connectionRequestDatabaseRecord.protocol_version.toUByte(),
                connectionRequestDatabaseRecord.username != null,
                connectionRequestDatabaseRecord.password != null,
                connectionRequestDatabaseRecord.will_retain == 1L,
                connectionRequestDatabaseRecord.will_qos.toQos(),
                connectionRequestDatabaseRecord.will_flag == 1L,
                connectionRequestDatabaseRecord.clean_start == 1L,
                connectionRequestDatabaseRecord.keep_alive_seconds.toInt(),
                ConnectionRequest.VariableHeader.Properties(
                    connectionRequestDatabaseRecord.session_expiry_interval_seconds?.toULong(),
                    connectionRequestDatabaseRecord.receive_maximum?.toInt(),
                    connectionRequestDatabaseRecord.maximum_packet_size?.toULong(),
                    connectionRequestDatabaseRecord.topic_alias_maximum?.toInt(),
                    connectionRequestDatabaseRecord.request_response_information.toNullableBoolean(),
                    connectionRequestDatabaseRecord.request_problem_information.toNullableBoolean(),
                    userProps,
                    auth,
                ),
            )
        val willProperties =
            if (connectionRequestDatabaseRecord.has_will_properties == 1L) {
                ConnectionRequest.Payload.WillProperties(
                    connectionRequestDatabaseRecord.will_property_will_delay_interval_seconds,
                    connectionRequestDatabaseRecord.will_property_payload_format_indicator == 1L,
                    connectionRequestDatabaseRecord.will_property_message_expiry_interval_seconds,
                    connectionRequestDatabaseRecord.will_property_content_type,
                    connectionRequestDatabaseRecord.will_property_response_topic?.let {
                        TopicName.fromOrThrow(it)
                    },
                    connectionRequestDatabaseRecord.will_property_correlation_data?.let { BufferFactory.Default.wrap(it) },
                    willUserProps,
                )
            } else {
                null
            }
        val payload =
            ConnectionRequest.Payload(
                connectionRequestDatabaseRecord.client_id,
                willProperties,
                connectionRequestDatabaseRecord.will_topic?.let { TopicName.fromOrThrow(it) },
                willPayload,
                connectionRequestDatabaseRecord.username,
                connectionRequestDatabaseRecord.password,
            )
        val connectionRequest = ConnectionRequest(variable, payload)
        val socketConnections = socketConnectionQueries.connectionsByBrokerId(id)
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
                            if (!it.websocket_protocols.isNullOrEmpty()) {
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
    ) {
        if (packet.qualityOfService == QualityOfService.AT_MOST_ONCE) return
        val brokerId = broker.identifier.toLong()
        val incoming = 1L
        val p = packet as PublishMessageV5<*>
        val payload = p.payloadAsByteArrayOrNull()
        withContext(dispatcher) {
            pubQueries.transaction {
                val subIds =
                    if (p.properties.subscriptionIdentifier.isEmpty()) {
                        null
                    } else {
                        p.properties.subscriptionIdentifier.joinToString()
                    }
                pubQueries.insertPublishMessage(
                    brokerId,
                    incoming,
                    if (p.dup) 1L else 0L,
                    p.qualityOfService.integerValue.toLong(),
                    if (p.retain) 1L else 0L,
                    p.topic.toString(),
                    p.packetIdentifier.toLong(),
                    p.properties.payloadFormatIndicator.toLong(),
                    p.properties.messageExpiryInterval,
                    p.properties.topicAlias?.toLong(),
                    p.properties.responseTopic?.toString(),
                    @Suppress("NoByteArrayInProd") // SQLDelight BLOB boundary (correlationData)
                    p.properties.correlationData?.let { it.readByteArray(it.remaining()) },
                    subIds,
                    p.properties.contentType,
                    payload,
                )
                for ((key, value) in p.properties.userProperty) {
                    propertyQueries.addProp(brokerId, incoming, p.packetIdentifier.toLong(), key, value)
                }
            }
        }
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
                    val props =
                        propertyQueries
                            .allProps(row.broker_id, row.incoming, row.packet_id)
                            .executeAsList()
                            .map { (key, value) -> Pair(key, value) }
                    val properties =
                        PublishMessageV5.Properties(
                            row.payload_format_indicator == 1L,
                            row.message_expiry_interval,
                            row.topic_alias?.toInt(),
                            row.response_topic?.let { t -> TopicName.fromOrThrow(t) },
                            row.correlation_data?.let { c -> BufferFactory.Default.wrap(c) },
                            props,
                            row.subscription_identifier
                                ?.split(", ")
                                ?.map { i -> i.toLong() }
                                ?.toSet() ?: emptySet(),
                            row.content_type,
                        )
                    val pub =
                        PublishMessageV5.ofRaw(
                            topic = TopicName.fromOrThrow(row.topic_name),
                            qos = row.qos.toQos(),
                            payload = payload,
                            dup = row.dup == 1L,
                            retain = row.retain == 1L,
                            packetIdentifier = row.packet_id.toInt(),
                            properties = properties,
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

    private fun Long?.toNullableBoolean(): Boolean? {
        val value = this ?: return null
        return value == 1L
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
                val props =
                    propertyQueries
                        .allProps(it.broker_id, it.incoming, it.packet_id)
                        .executeAsList()
                        .map { (key, value) -> Pair(key, value) }
                val properties =
                    PublishMessageV5.Properties(
                        it.payload_format_indicator == 1L,
                        it.message_expiry_interval,
                        it.topic_alias?.toInt(),
                        it.response_topic?.let { t -> TopicName.fromOrThrow(t) },
                        it.correlation_data?.let { c -> BufferFactory.Default.wrap(c) },
                        props,
                        it.subscription_identifier
                            ?.split(", ")
                            ?.map { i -> i.toLong() }
                            ?.toSet() ?: emptySet(),
                        it.content_type,
                    )
                PublishMessageV5.ofRaw(
                    topic = TopicName.fromOrThrow(it.topic_name),
                    qos = it.qos.toQos(),
                    payload = payload,
                    dup = true,
                    retain = it.retain == 1L,
                    packetIdentifier = it.packet_id.toInt(),
                    properties = properties,
                )
            }
        map +=
            qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().map {
                val userProps =
                    propertyQueries
                        .allProps(it.broker_id, it.incoming, it.packet_id) { k, v ->
                            Pair(k, v)
                        }.executeAsList()
                when (it.type) {
                    5L ->
                        PublishReceived(
                            AckVariableHeader(
                                it.packet_id.toInt(),
                                when (it.reason_code.toUByte()) {
                                    ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
                                    ReasonCode.NO_MATCHING_SUBSCRIBERS.byte -> ReasonCode.NO_MATCHING_SUBSCRIBERS
                                    ReasonCode.UNSPECIFIED_ERROR.byte -> ReasonCode.UNSPECIFIED_ERROR
                                    ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR.byte -> ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
                                    ReasonCode.NOT_AUTHORIZED.byte -> ReasonCode.NOT_AUTHORIZED
                                    ReasonCode.TOPIC_NAME_INVALID.byte -> ReasonCode.TOPIC_NAME_INVALID
                                    ReasonCode.PACKET_IDENTIFIER_IN_USE.byte -> ReasonCode.PACKET_IDENTIFIER_IN_USE
                                    ReasonCode.QUOTA_EXCEEDED.byte -> ReasonCode.QUOTA_EXCEEDED
                                    ReasonCode.PAYLOAD_FORMAT_INVALID.byte -> ReasonCode.PAYLOAD_FORMAT_INVALID
                                    else -> error("Invalid PublishReceived QOS Reason code ${it.reason_code}")
                                },
                                AckProperties(it.reason_string, userProps),
                            ),
                        )

                    6L ->
                        PublishRelease(
                            AckVariableHeader(
                                it.packet_id.toInt(),
                                pubRelOrPubCompReasonCode(it.reason_code.toInt()),
                                AckProperties(it.reason_string, userProps),
                            ),
                        )

                    7L ->
                        PublishComplete(
                            AckVariableHeader(
                                it.packet_id.toInt(),
                                pubRelOrPubCompReasonCode(it.reason_code.toInt()),
                                AckProperties(it.reason_string, userProps),
                            ),
                        )

                    else -> throw IllegalArgumentException("Unexpected type ${it.type}")
                }
            }

        map +=
            subQueries.queuedSubMessages(broker.identifier.toLong()).executeAsList().map { subscribeRequest ->
                val userProps =
                    propertyQueries
                        .allProps(subscribeRequest.broker_id, 0L, subscribeRequest.packet_id) { k, v ->
                            Pair(k, v)
                        }.executeAsList()
                val subs =
                    subscriptionQueries
                        .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                        .executeAsList()
                        .map {
                            Subscription(
                                TopicFilter.fromOrThrow(it.topic_filter),
                                it.qos.toQos(),
                                it.no_local == 1L,
                                it.retain_as_published == 1L,
                                when (it.retain_handling) {
                                    1L -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
                                    2L -> ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
                                    else -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
                                },
                            )
                        }.toSet()
                SubscribeRequest(
                    SubscribeRequest.VariableHeader(
                        subscribeRequest.packet_id.toInt(),
                        SubscribeRequest.VariableHeader.Properties(subscribeRequest.reason_string, userProps),
                    ),
                    subs,
                )
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
                        val userProps =
                            propertyQueries
                                .allProps(unsubscribeRequest.broker_id, 0L, unsubscribeRequest.packet_id) { k, v ->
                                    Pair(k, v)
                                }.executeAsList()
                        UnsubscribeRequest(
                            UnsubscribeRequest.VariableHeader(
                                unsubscribeRequest.packet_id.toInt(),
                                UnsubscribeRequest.VariableHeader.Properties(userProps),
                            ),
                            subscriptions,
                        )
                    } else {
                        null
                    }
                }
        return map.sortedBy { it.packetIdentifier }
    }

    private fun pubRelOrPubCompReasonCode(code: Int): ReasonCode =
        when (code.toUByte()) {
            ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
            ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte -> ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
            else -> error("Invalid PublishRelease QOS Reason code $code")
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
        val brokerId = broker.identifier.toLong()
        val incoming = 0L
        val p = pub as PublishMessageV5<*>
        val payload = p.payloadAsByteArrayOrNull()
        val packetId =
            withContext(dispatcher) {
                packetIdMutex.withLock {
                    brokerQueries.transactionWithResult {
                        val packetId = brokerQueries.nextPacketId(brokerId).executeAsOne().toLong()
                        brokerQueries.incrementPacketId(brokerId)
                        val subIds =
                            if (p.properties.subscriptionIdentifier.isEmpty()) {
                                null
                            } else {
                                p.properties.subscriptionIdentifier.joinToString()
                            }
                        pubQueries.insertPublishMessage(
                            brokerId,
                            incoming,
                            if (p.dup) 1L else 0L,
                            p.qualityOfService.integerValue.toLong(),
                            if (p.retain) 1L else 0L,
                            p.topic.toString(),
                            packetId,
                            p.properties.payloadFormatIndicator.toLong(),
                            p.properties.messageExpiryInterval,
                            p.properties.topicAlias?.toLong(),
                            p.properties.responseTopic?.toString(),
                            @Suppress("NoByteArrayInProd") // SQLDelight BLOB boundary (correlationData)
                            p.properties.correlationData
                                ?.let { it.readByteArray(it.remaining()) },
                            subIds,
                            p.properties.contentType,
                            payload,
                        )
                        for ((key, value) in p.properties.userProperty) {
                            propertyQueries.addProp(brokerId, incoming, packetId, key, value)
                        }
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
        val p =
            pubQueries
                .messageWithId(broker.identifier.toLong(), 0L, packetId.toLong())
                .executeAsOneOrNull() ?: return null
        val payload =
            if (p.payload != null) {
                BufferFactory.Default.wrap(p.payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
        val props =
            propertyQueries
                .allProps(p.broker_id, p.incoming, p.packet_id)
                .executeAsList()
                .map { (key, value) -> Pair(key, value) }
        val properties =
            PublishMessageV5.Properties(
                p.payload_format_indicator == 1L,
                p.message_expiry_interval,
                p.topic_alias?.toInt(),
                p.response_topic?.let { t -> TopicName.fromOrThrow(t) },
                p.correlation_data?.let { c -> BufferFactory.Default.wrap(c) },
                props,
                p.subscription_identifier
                    ?.split(", ")
                    ?.map { i -> i.toLong() }
                    ?.toSet() ?: emptySet(),
                p.content_type,
            )
        return PublishMessageV5.ofRaw(
            topic = TopicName.fromOrThrow(p.topic_name),
            qos = p.qos.toQos(),
            payload = payload,
            dup = p.dup == 1L,
            retain = p.retain == 1L,
            packetIdentifier = p.packet_id.toInt(),
            properties = properties,
        )
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest {
        val subscribeRequest = sub as SubscribeRequest
        val packetId =
            withContext(dispatcher) {
                packetIdMutex.withLock {
                    subQueries.transactionWithResult {
                        val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne()
                        brokerQueries.incrementPacketId(broker.identifier.toLong())
                        subQueries.insertSubscribeRequest(
                            broker.identifier.toLong(),
                            packetId,
                            subscribeRequest.variable.properties.reasonString,
                        )
                        subscribeRequest.subscriptions.forEach {
                            subscriptionQueries.insertSubscription(
                                broker.identifier.toLong(),
                                packetId,
                                it.topicFilter.toString(),
                                it.maximumQos.integerValue.toLong(),
                                it.noLocal.toLong(),
                                it.retainAsPublished.toLong(),
                                it.retainHandling.value.toLong(),
                            )
                        }
                        for ((key, value) in subscribeRequest.variable.properties.userProperty) {
                            propertyQueries.addProp(broker.identifier.toLong(), 0L, packetId, key, value)
                        }
                        packetId
                    }
                }
            }
        return subscribeRequest.copyWithNewPacketIdentifier(packetId.toInt())
    }

    override suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest? {
        val subscribeRequest =
            subQueries
                .messageWithId(broker.identifier.toLong(), packetId.toLong())
                .executeAsOneOrNull() ?: return null
        val userProps =
            propertyQueries
                .allProps(subscribeRequest.broker_id, 0L, subscribeRequest.packet_id) { k, v ->
                    Pair(k, v)
                }.executeAsList()
        val subs =
            subscriptionQueries
                .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                .executeAsList()
                .map {
                    Subscription(
                        TopicFilter.fromOrThrow(it.topic_filter),
                        it.qos.toQos(),
                        it.no_local == 1L,
                        it.retain_as_published == 1L,
                        when (it.retain_handling) {
                            1L -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
                            2L -> ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
                            else -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
                        },
                    )
                }.toSet()
        return SubscribeRequest(
            SubscribeRequest.VariableHeader(
                subscribeRequest.packet_id.toInt(),
                SubscribeRequest.VariableHeader.Properties(subscribeRequest.reason_string, userProps),
            ),
            subs,
        )
    }

    override suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int =
        withContext(dispatcher) {
            val unsubscribe = unsub as UnsubscribeRequest

            packetIdMutex.withLock {
                unsubQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne()
                    brokerQueries.incrementPacketId(broker.identifier.toLong())
                    unsubQueries.insertUnsubscribeRequest(broker.identifier.toLong(), packetId)
                    unsub.topics.forEach {
                        subscriptionQueries.addUnsubscriptionPacketId(
                            packetId,
                            broker.identifier.toLong(),
                            it.toString(),
                        )
                    }
                    for ((key, value) in unsubscribe.variable.properties.userProperty) {
                        propertyQueries.addProp(broker.identifier.toLong(), 0L, packetId, key, value)
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
            val userProps =
                propertyQueries
                    .allProps(unsubscribeRequest.broker_id, 0L, unsubscribeRequest.packet_id) { k, v ->
                        Pair(k, v)
                    }.executeAsList()
            UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(
                    unsubscribeRequest.packet_id.toInt(),
                    UnsubscribeRequest.VariableHeader.Properties(userProps),
                ),
                subscriptions,
            )
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
