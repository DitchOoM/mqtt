package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
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
        topicName: TopicName,
        payload: ReadBuffer?,
        payloadFormatIndicator: Boolean,
        messageExpiryInterval: Long?,
        topicAlias: Int?,
        responseTopic: TopicName?,
        correlationData: ReadBuffer?,
        userProperty: List<Pair<String, String>>,
        subscriptionIdentifier: Set<Long>,
        contentType: String?,
    ): PublishMessage =
        ControlPacketV5.Publish.ofRaw(
            topic = topicName,
            qos = qos,
            payload = payload,
            dup = dup,
            retain = retain,
            packetIdentifier = NO_PACKET_ID,
            properties =
                PublishProperties(
                    payloadFormatIndicator,
                    messageExpiryInterval,
                    topicAlias,
                    responseTopic,
                    correlationData,
                    userProperty,
                    subscriptionIdentifier,
                    contentType,
                ),
        )

    override fun subscribe(
        topicFilter: TopicFilter,
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
    ): ISubscribeRequest =
        SubscribeRequest(
            packetIdentifier = NO_PACKET_ID.toUShort(),
            subscriptions = subscriptions,
            userProperty = userProperty,
        )

    override fun unsubscribe(
        topics: Set<TopicFilter>,
        userProperty: List<Pair<String, String>>,
    ) = UnsubscribeRequest(topics, userProperty)

    override fun disconnect(
        reasonCode: ReasonCode,
        sessionExpiryIntervalSeconds: ULong?,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): IDisconnectNotification =
        DisconnectNotification(
            reasonCode = reasonCode,
            sessionExpiryIntervalSeconds = sessionExpiryIntervalSeconds,
            reasonString = reasonString,
            userProperty = userProperty,
        )

    override suspend fun defaultPersistence(
        androidContext: Any?,
        name: String,
        inMemory: Boolean,
    ): Persistence = newDefaultPersistence(androidContext, name, inMemory)
}
