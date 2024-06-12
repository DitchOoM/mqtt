package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.persistence.newDefaultPersistence

object ControlPacketV5Factory : ControlPacketFactory {
    override val protocolVersion: Int = 5

    override fun from(
        buffer: ReadBuffer,
        byte1: UByte,
        remainingLength: Int,
    ) = ControlPacketV5.from(buffer, byte1, remainingLength)

    override fun pingRequest() = PingRequest

    override fun pingResponse() = PingResponse

    override fun publish(
        dup: Boolean,
        qos: QualityOfService,
        retain: Boolean,
        topicName: Topic,
        payload: ReadBuffer?,
        payloadFormatIndicator: Boolean,
        messageExpiryInterval: Long?,
        topicAlias: Int?,
        responseTopic: Topic?,
        correlationData: ReadBuffer?,
        userProperty: List<Pair<String, String>>,
        subscriptionIdentifier: Set<Long>,
        contentType: String?,
    ): IPublishMessage {
        val fixedHeader = PublishMessage.FixedHeader(dup, qos, retain)
        val properties =
            PublishMessage.VariableHeader.Properties(
                payloadFormatIndicator,
                messageExpiryInterval,
                topicAlias,
                responseTopic,
                correlationData,
                userProperty,
                subscriptionIdentifier,
                contentType,
            )
        val variableHeader = PublishMessage.VariableHeader(topicName, NO_PACKET_ID, properties)
        return PublishMessage(fixedHeader, variableHeader, payload)
    }

    override fun subscribe(
        topicFilter: Topic,
        maximumQos: QualityOfService,
        noLocal: Boolean,
        retainAsPublished: Boolean,
        retainHandling: ISubscription.RetainHandling,
        serverReference: String?,
        userProperty: List<Pair<String, String>>,
    ): ISubscribeRequest {
        val subscription = Subscription(topicFilter, maximumQos)
        return subscribe(
            setOf(subscription),
            serverReference,
            userProperty,
        )
    }

    override fun subscribe(
        subscriptions: Set<ISubscription>,
        serverReference: String?,
        userProperty: List<Pair<String, String>>,
    ): ISubscribeRequest {
        val props =
            SubscribeRequest.VariableHeader.Properties(
                reasonString = "",
                userProperty = userProperty,
            )
        val variableHeader = SubscribeRequest.VariableHeader(NO_PACKET_ID, props)
        return SubscribeRequest(variableHeader, subscriptions)
    }

    override fun unsubscribe(
        topics: Set<Topic>,
        userProperty: List<Pair<String, String>>,
    ) = UnsubscribeRequest(topics, userProperty)

    override fun disconnect(
        reasonCode: ReasonCode,
        sessionExpiryIntervalSeconds: ULong?,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): IDisconnectNotification {
        val props =
            DisconnectNotification.VariableHeader.Properties(
                sessionExpiryIntervalSeconds,
                reasonString,
                userProperty,
            )
        return DisconnectNotification(DisconnectNotification.VariableHeader(reasonCode, props))
    }

    override suspend fun defaultPersistence(
        androidContext: Any?,
        name: String,
        inMemory: Boolean,
    ): Persistence = newDefaultPersistence(androidContext, name, inMemory)
}
