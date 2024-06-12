package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt3.persistence.newDefaultPersistence

object ControlPacketV4Factory : ControlPacketFactory {
    override val protocolVersion: Int = 4

    override suspend fun defaultPersistence(
        androidContext: Any?,
        name: String,
        inMemory: Boolean,
    ): Persistence = newDefaultPersistence(androidContext, name, inMemory)

    override fun from(
        buffer: ReadBuffer,
        byte1: UByte,
        remainingLength: Int,
    ) = ControlPacketV4.from(buffer, byte1, remainingLength)

    override fun pingRequest() = PingRequest

    override fun pingResponse() = PingResponse

    override fun publish(
        dup: Boolean,
        qos: QualityOfService,
        retain: Boolean,
        topicName: Topic,
        payload: ReadBuffer?,
        // MQTT 5 Properties, Should be ignored in this version
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
        val variableHeader = PublishMessage.VariableHeader(topicName, NO_PACKET_ID)
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
    ): ISubscribeRequest = SubscribeRequest(NO_PACKET_ID, subscriptions)

    override fun unsubscribe(
        topics: Set<Topic>,
        userProperty: List<Pair<String, String>>,
    ) = UnsubscribeRequest(NO_PACKET_ID, topics)

    override fun disconnect(
        reasonCode: ReasonCode,
        sessionExpiryIntervalSeconds: ULong?,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = DisconnectNotification
}
