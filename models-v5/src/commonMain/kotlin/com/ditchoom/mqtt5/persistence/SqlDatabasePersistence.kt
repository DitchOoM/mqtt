package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver
import com.ditchoom.Mqtt5
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
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishMessage
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

class SqlDatabasePersistence(driver: SqlDriver) : Persistence {
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
        val publishRelease = pubRel as PublishRelease
        qos2Messages.transaction {
            pubQueries.deletePublishMessage(broker.identifier.toLong(), 0L, incomingPubRecv.packetIdentifier.toLong())
            qos2Messages.insertQos2Message(
                broker.identifier.toLong(),
                0L,
                incomingPubRecv.packetIdentifier.toLong(),
                publishRelease.variable.reasonCode.byte.toLong(),
                publishRelease.variable.properties.reasonString,
                publishRelease.controlPacketValue.toLong()
            )
            for ((key, value) in publishRelease.variable.properties.userProperty) {
                propertyQueries.addProp(
                    broker.identifier.toLong(),
                    0L,
                    incomingPubRecv.packetIdentifier.toLong(),
                    key,
                    value
                )
            }
        }
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete
    ) = withContext(dispatcher) {
        qos2Messages.transaction {
            qos2Messages.updateQos2Message(
                outPubComp.controlPacketValue.toLong(),
                broker.identifier.toLong(),
                1L,
                incomingPubRel.packetIdentifier.toLong()
            )
            val userProperty = (outPubComp as PublishComplete).variable.properties.userProperty
            if (userProperty.isNotEmpty()) {
                for ((key, value) in userProperty) {
                    propertyQueries.addProp(
                        broker.identifier.toLong(),
                        1L,
                        outPubComp.packetIdentifier.toLong(),
                        key,
                        value
                    )
                }
            }
        }
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
            val authPayload = connect.variableHeader.properties.authentication?.data
            val authData = authPayload?.let { it.readByteArray(it.remaining()) }
            authPayload?.resetForRead()
            val correlationData = connect.payload.willProperties?.correlationData
            val willPropsCorrelationData = correlationData?.let { it.readByteArray(it.remaining()) }
            correlationData?.resetForRead()
            connectionRequestQueries.insertConnectionRequest(
                brokerId,
                connect.protocolName,
                connect.protocolVersion.toLong(),
                connect.variableHeader.willRetain.toLong(),
                connect.variableHeader.willQos.integerValue.toLong(),
                connect.variableHeader.willFlag.toLong(),
                connect.variableHeader.cleanStart.toLong(),
                connect.variableHeader.keepAliveSeconds.toLong(),
                connect.variableHeader.properties.sessionExpiryIntervalSeconds?.toLong(),
                connect.variableHeader.properties.receiveMaximum?.toLong(),
                connect.variableHeader.properties.maximumPacketSize?.toLong(),
                connect.variableHeader.properties.topicAliasMaximum?.toLong(),
                connect.variableHeader.properties.requestResponseInformation?.toLong(),
                connect.variableHeader.properties.requestProblemInformation?.toLong(),
                connect.variableHeader.properties.authentication?.method,
                authData,
                connect.payload.clientId,
                (connect.payload.willProperties != null).toLong(),
                connect.payload.willTopic?.toString(),
                willPayloadByteArray,
                connect.payload.userName,
                connect.payload.password,
                connect.payload.willProperties?.willDelayIntervalSeconds ?: 0L,
                connect.payload.willProperties?.payloadFormatIndicator?.toLong(),
                connect.payload.willProperties?.messageExpiryIntervalSeconds,
                connect.payload.willProperties?.contentType,
                connect.payload.willProperties?.responseTopic?.toString(),
                willPropsCorrelationData
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
        return MqttBroker(brokerId.toInt(), connectionOps.toSet(), connectionRequest)
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
                val auth = if (connectionRequestDatabaseRecord.authentication_method != null &&
                    connectionRequestDatabaseRecord.authentication_data != null
                ) {
                    Authentication(
                        connectionRequestDatabaseRecord.authentication_method,
                        PlatformBuffer.wrap(connectionRequestDatabaseRecord.authentication_data)
                    )
                } else {
                    null
                }
                val userProps = propertyQueries.allProps(broker.id, 0L, -1) { k, v ->
                    Pair(k, v)
                }.executeAsList()
                val willUserProps = propertyQueries.allProps(broker.id, 0L, -2) { k, v ->
                    Pair(k, v)
                }.executeAsList()
                val variable = ConnectionRequest.VariableHeader(
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
                        auth
                    )
                )
                val willProperties = if (connectionRequestDatabaseRecord.has_will_properties == 1L) {
                    ConnectionRequest.Payload.WillProperties(
                        connectionRequestDatabaseRecord.will_property_will_delay_interval_seconds,
                        connectionRequestDatabaseRecord.will_property_payload_format_indicator == 1L,
                        connectionRequestDatabaseRecord.will_property_message_expiry_interval_seconds,
                        connectionRequestDatabaseRecord.will_property_content_type,
                        connectionRequestDatabaseRecord.will_property_response_topic?.let {
                            Topic.fromOrThrow(
                                it,
                                Topic.Type.Name
                            )
                        },
                        connectionRequestDatabaseRecord.will_property_correlation_data?.let { PlatformBuffer.wrap(it) },
                        willUserProps
                    )
                } else {
                    null
                }
                val payload = ConnectionRequest.Payload(
                    connectionRequestDatabaseRecord.client_id,
                    willProperties,
                    connectionRequestDatabaseRecord.will_topic?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
                    willPayload,
                    connectionRequestDatabaseRecord.username,
                    connectionRequestDatabaseRecord.password
                )
                val connectionRequest = ConnectionRequest(variable, payload)
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
            val reply = replyMessage as PublishReceived
            qos2Messages.insertQos2Message(
                broker.identifier.toLong(),
                1L,
                packet.packetIdentifier.toLong(),
                reply.variable.reasonCode.byte.toLong(),
                reply.variable.properties.reasonString,
                reply.controlPacketValue.toLong()
            )
        }

    private fun Long.toQos(): QualityOfService {
        return when (this) {
            1L -> QualityOfService.AT_LEAST_ONCE
            2L -> QualityOfService.EXACTLY_ONCE
            else -> QualityOfService.AT_MOST_ONCE
        }
    }

    private fun Long?.toNullableBoolean(): Boolean? {
        val value = this ?: return null
        return value == 1L
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val map = ArrayList<ControlPacket>()
        map += pubQueries.queuedPubMessages(broker.identifier.toLong()).executeAsList().map {
            val payload = if (it.payload != null) {
                PlatformBuffer.wrap(it.payload, ByteOrder.BIG_ENDIAN)
            } else {
                null
            }
            val props = propertyQueries.allProps(it.broker_id, it.incoming, it.packet_id).executeAsList()
                .map { (key, value) -> Pair(key, value) }
            val properties = PublishMessage.VariableHeader.Properties(
                it.payload_format_indicator == 1L,
                it.message_expiry_interval,
                it.topic_alias?.toInt(),
                it.response_topic?.let { t -> Topic.fromOrThrow(t, Topic.Type.Name) },
                it.correlation_data?.let { c -> PlatformBuffer.wrap(c) },
                props,
                it.subscription_identifier?.split(", ")?.map { i -> i.toLong() }?.toSet() ?: emptySet(),
                it.content_type
            )
            PublishMessage(
                PublishMessage.FixedHeader(true, it.qos.toQos(), it.retain == 1L),
                PublishMessage.VariableHeader(
                    Topic.fromOrThrow(it.topic_name, Topic.Type.Name),
                    it.packet_id.toInt(),
                    properties
                ),
                payload
            )
        }
        map += qos2Messages.allMessages(broker.identifier.toLong()).executeAsList().map {
            val userProps = propertyQueries
                .allProps(it.broker_id, it.incoming, it.packet_id) { k, v ->
                    Pair(k, v)
                }
                .executeAsList()
            when (it.type) {
                5L -> PublishReceived(
                    PublishReceived.VariableHeader(
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
                        PublishReceived.VariableHeader.Properties(it.reason_string, userProps)
                    )
                )

                6L -> PublishRelease(
                    PublishRelease.VariableHeader(
                        it.packet_id.toInt(),
                        pubRelOrPubCompReasonCode(it.reason_code.toInt()),
                        PublishRelease.VariableHeader.Properties(it.reason_string, userProps)
                    )
                )

                7L -> PublishComplete(
                    PublishComplete.VariableHeader(
                        it.packet_id.toInt(),
                        pubRelOrPubCompReasonCode(it.reason_code.toInt()),
                        PublishComplete.VariableHeader.Properties(it.reason_string, userProps)
                    )
                )

                else -> throw IllegalArgumentException("Unexpected type ${it.type}")
            }
        }

        map += subQueries.queuedSubMessages(broker.identifier.toLong()).executeAsList().map { subscribeRequest ->
            val userProps = propertyQueries
                .allProps(subscribeRequest.broker_id, 0L, subscribeRequest.packet_id) { k, v ->
                    Pair(k, v)
                }
                .executeAsList()
            val subs = subscriptionQueries
                .queuedSubscriptions(subscribeRequest.broker_id, subscribeRequest.packet_id)
                .executeAsList().map {
                    Subscription(
                        Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter),
                        it.qos.toQos(),
                        it.no_local == 1L,
                        it.retain_as_published == 1L,
                        when (it.retain_handling) {
                            1L -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
                            2L -> ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
                            else -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
                        }
                    )
                }.toSet()
            SubscribeRequest(
                SubscribeRequest.VariableHeader(
                    subscribeRequest.packet_id.toInt(),
                    SubscribeRequest.VariableHeader.Properties(subscribeRequest.reason_string, userProps)
                ),
                subs
            )
        }
        map += unsubQueries.queuedUnsubMessages(broker.identifier.toLong()).executeAsList()
            .mapNotNull { unsubscribeRequest ->
                val subscriptions = subscriptionQueries
                    .queuedUnsubscriptions(unsubscribeRequest.broker_id, unsubscribeRequest.packet_id)
                    .executeAsList().map {
                        Topic.fromOrThrow(it.topic_filter, Topic.Type.Filter)
                    }.toSet()
                if (subscriptions.isNotEmpty()) {
                    val userProps = propertyQueries
                        .allProps(unsubscribeRequest.broker_id, 0L, unsubscribeRequest.packet_id) { k, v ->
                            Pair(k, v)
                        }
                        .executeAsList()
                    UnsubscribeRequest(
                        UnsubscribeRequest.VariableHeader(
                            unsubscribeRequest.packet_id.toInt(),
                            UnsubscribeRequest.VariableHeader.Properties(userProps)
                        ),
                        subscriptions
                    )
                } else {
                    null
                }
            }
        return map.sortedBy { it.packetIdentifier }
    }

    private fun pubRelOrPubCompReasonCode(code: Int): ReasonCode = when (code.toUByte()) {
        ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
        ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte -> ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
        else -> error("Invalid PublishRelease QOS Reason code $code")
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
        val brokerId = broker.identifier.toLong()
        val incoming = 0L
        val p = pub as PublishMessage
        val payload = p.payload?.readByteArray(p.payload.remaining())
        p.payload?.resetForRead()
        val packetId = withContext(dispatcher) {
            packetIdMutex.withLock {
                brokerQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(brokerId).executeAsOne().toLong()
                    brokerQueries.incrementPacketId(brokerId)
                    val subIds = if (p.variable.properties.subscriptionIdentifier.isEmpty()) {
                        null
                    } else {
                        p.variable.properties.subscriptionIdentifier.joinToString()
                    }
                    pubQueries.insertPublishMessage(
                        brokerId,
                        incoming,
                        if (p.fixed.dup) 1L else 0L,
                        p.fixed.qos.integerValue.toLong(),
                        if (p.fixed.retain) 1L else 0L,
                        p.topic.toString(),
                        packetId,
                        p.variable.properties.payloadFormatIndicator.toLong(),
                        p.variable.properties.messageExpiryInterval,
                        p.variable.properties.topicAlias?.toLong(),
                        p.variable.properties.responseTopic?.toString(),
                        p.variable.properties.correlationData?.let { it.readByteArray(it.remaining()) },
                        subIds,
                        p.variable.properties.contentType,
                        payload
                    )
                    for ((key, value) in p.variable.properties.userProperty) {
                        propertyQueries.addProp(brokerId, incoming, packetId, key, value)
                    }
                    packetId.toInt()
                }
            }
        }
        return packetId
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest
    ): ISubscribeRequest {
        val subscribeRequest = sub as SubscribeRequest
        val packetId = withContext(dispatcher) {
            packetIdMutex.withLock {
                subQueries.transactionWithResult {
                    val packetId = brokerQueries.nextPacketId(broker.identifier.toLong()).executeAsOne()
                    brokerQueries.incrementPacketId(broker.identifier.toLong())
                    subQueries.insertSubscribeRequest(
                        broker.identifier.toLong(),
                        packetId,
                        subscribeRequest.variable.properties.reasonString
                    )
                    subscribeRequest.subscriptions.forEach {
                        subscriptionQueries.insertSubscription(
                            broker.identifier.toLong(),
                            packetId,
                            it.topicFilter.toString(),
                            it.maximumQos.integerValue.toLong(),
                            it.noLocal.toLong(),
                            it.retainAsPublished.toLong(),
                            it.retainHandling.value.toLong()
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

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        return withContext(dispatcher) {
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
                            it.toString()
                        )
                    }
                    for ((key, value) in unsubscribe.variable.properties.userProperty) {
                        propertyQueries.addProp(broker.identifier.toLong(), 0L, packetId, key, value)
                    }
                    packetId.toInt()
                }
            }
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
