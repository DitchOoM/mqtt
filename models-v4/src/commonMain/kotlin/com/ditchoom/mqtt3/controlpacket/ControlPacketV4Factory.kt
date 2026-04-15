package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
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
        topicName: TopicName,
        payload: ReadBuffer?,
        // MQTT 5 Properties, ignored for v4
        payloadFormatIndicator: Boolean,
        messageExpiryInterval: Long?,
        topicAlias: Int?,
        responseTopic: TopicName?,
        correlationData: ReadBuffer?,
        userProperty: List<Pair<String, String>>,
        subscriptionIdentifier: Set<Long>,
        contentType: String?,
    ): PublishMessage =
        PublishMessageV4.ofRaw(
            topic = topicName,
            qos = qos,
            payload = payload,
            dup = dup,
            retain = retain,
            packetIdentifier = NO_PACKET_ID,
        )

    override fun <P> publish(
        dup: Boolean,
        qos: QualityOfService,
        retain: Boolean,
        topicName: TopicName,
        payload: P,
        encodePayload: (WriteBuffer, P) -> Unit,
        payloadSize: (P) -> Int,
    ): PublishMessage {
        val codec =
            object : PayloadCodec<P> {
                override fun decode(buffer: ReadBuffer): P =
                    error("Outgoing publish codec is not used for decoding")

                override fun encode(
                    buffer: WriteBuffer,
                    value: P,
                ) {
                    encodePayload(buffer, value)
                }

                override fun encodedSize(value: P): Int = payloadSize(value)
            }
        return PublishMessageV4.ofTyped(
            topic = topicName,
            qos = qos,
            payload = payload,
            codec = codec,
            dup = dup,
            retain = retain,
            packetIdentifier = NO_PACKET_ID,
        )
    }

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
    ): ISubscribeRequest = SubscribeRequest(NO_PACKET_ID, subscriptions)

    override fun unsubscribe(
        topics: Set<TopicFilter>,
        userProperty: List<Pair<String, String>>,
    ) = UnsubscribeRequest(NO_PACKET_ID, topics)

    override fun disconnect(
        reasonCode: ReasonCode,
        sessionExpiryIntervalSeconds: ULong?,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = DisconnectNotification
}
