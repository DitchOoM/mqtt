package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
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

    // TODO(buffer-v1, Phase A): default decode path is being removed entirely. Under the
    //  v1 contract each consumer constructs its own ControlPacketV4Codec(payloadCodec).
    //  IPC sites (MqttCodec, RemoteMqttClientWorker, AndroidRemoteMqttClient, MessageHelper)
    //  migrate in Phase B; this stub keeps the interface contract until then.
    override fun from(buffer: ReadBuffer): com.ditchoom.mqtt.controlpacket.ControlPacket =
        throw UnsupportedOperationException(
            "ControlPacketV4Factory.from(buffer) is deferred under buffer-v1: construct " +
                "ControlPacketV4Codec(yourPayloadCodec).decode(buffer, ctx) directly.",
        )

    override fun pingRequest() = PingRequest()

    override fun pingResponse() = PingResponse()

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
    ): PublishMessage {
        // TODO(buffer-v1, Phase A): the factory used to wrap raw ReadBuffer payloads as
        //  BufferPayload. Under v1 the PUBLISH payload is consumer-typed; this convenience
        //  function is deferred and throws. Construct PublishMessageV4 directly with your
        //  typed Payload, or wait for Phase B which reshapes the IPC layers.
        throw UnsupportedOperationException(
            "ControlPacketV4Factory.publish(...) is deferred under buffer-v1: construct " +
                "PublishMessageV4(...) directly with a typed <P : Payload> payload.",
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
    ) = DisconnectNotification()
}
