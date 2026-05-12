package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.opaqueBytesFrom
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
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

    /**
     * Decode a v4 control-packet wire (`[byte1][VBI(remainingLength)][body]`) with PUBLISH
     * application bytes carried in an [OpaquePublishPayload] (Pattern #2 — consumer-owned
     * `PlatformBuffer`, byte-exact). Production decode at the connection layer should
     * route through `MqttCodec` so the topic-router lambda picks per-topic codecs zero-copy;
     * this path is the no-codec-knowledge fallback (IPC consumers, debug capture,
     * cross-version dispatch) where the consumer wants opaque bytes back.
     */
    override fun from(buffer: ReadBuffer): com.ditchoom.mqtt.controlpacket.ControlPacket =
        ControlPacketV4Codec(OpaquePublishPayloadCodec).decode(buffer, DecodeContext.Empty)

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
        val opaquePayload = OpaquePublishPayload(opaqueBytesFromOrEmpty(payload))
        val packetIdField =
            if (qos == QualityOfService.AT_MOST_ONCE) {
                null
            } else {
                // Real packet IDs are assigned by Persistence on enqueue; the factory
                // entry point uses NO_PACKET_ID as a sentinel before that step.
                NO_PACKET_ID.toUShort()
            }
        return PublishMessageV4(
            header = MqttFixedHeader(makePublishHeaderByte(dup, qos, retain)),
            topicName = topicName.toString(),
            packetId = packetIdField,
            payload = opaquePayload,
        )
    }

    /**
     * Compute the v4 PUBLISH fixed-header first byte from the dup/qos/retain bits.
     * Matches the wire layout in MQTT 3.1.1 §3.3.1.
     */
    private fun makePublishHeaderByte(
        dup: Boolean,
        qos: QualityOfService,
        retain: Boolean,
    ): UByte {
        val dupBit = if (dup) 0x08 else 0x00
        val retainBit = if (retain) 0x01 else 0x00
        val qosBits = qos.integerValue.toInt() shl 1
        return ((3 shl 4) or dupBit or qosBits or retainBit).toUByte()
    }

    /**
     * Wrap [src] into a consumer-owned [OpaquePublishPayload.handle]. Empty / null inputs
     * produce a zero-byte handle. Pattern #2: allocate a fresh [com.ditchoom.buffer.PlatformBuffer]
     * via [BufferFactory.Default], copy the wire bytes, hand ownership to the handle.
     */
    private fun opaqueBytesFromOrEmpty(src: ReadBuffer?): com.ditchoom.buffer.codec.OpaqueBytesHandle {
        val factory = BufferFactory.Default
        val remaining = src?.remaining() ?: 0
        val dst = factory.allocate(remaining)
        if (remaining > 0 && src != null) dst.write(src)
        dst.resetForRead()
        return opaqueBytesFrom(dst)
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
