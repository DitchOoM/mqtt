package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.codec.IdentityBufferCodec
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.PublishMessagePayloadMaterializer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize

/**
 * MQTT 5.0 PUBLISH packet, parameterized on the payload type [P].
 *
 * See [com.ditchoom.mqtt3.controlpacket.PublishMessageV4] for a description of the generic
 * approach. V5 adds variable-header properties and delegates the properties section to the
 * generated body codec via the `@MqttProperties` SPI binding.
 */
class PublishMessageV5<P> internal constructor(
    override val topic: TopicName,
    override val qualityOfService: QualityOfService,
    override val dup: Boolean,
    override val retain: Boolean,
    packetIdentifier: Int,
    val properties: Properties,
    override val payload: P,
    override val codec: PayloadCodec<P>,
) : PublishMessage, ControlPacketV5, PublishMessagePayloadMaterializer<P> {
    override val controlPacketValue: Byte get() = PublishMessage.CONTROL_PACKET_VALUE
    override val flags: Byte
        get() {
            val dupInt = if (dup) 0b1000 else 0b0
            val qosInt = qualityOfService.integerValue.toInt().shl(1)
            val retainInt = if (retain) 0b1 else 0b0
            return (dupInt or qosInt or retainInt).toByte()
        }
    override val packetIdentifier: Int = packetIdentifier

    private fun topicEncodedSize(): Int = UShort.SIZE_BYTES + topic.toString().utf8Length()

    private fun payloadSize(): Int = codec.encodedSize(payload)

    private fun propertiesSectionSize(): Int {
        val props = properties.props
        val bodySize = mqttPropertiesSize(props)
        return variableByteSize(bodySize) + bodySize
    }

    override fun remainingLength(): Int {
        var size = topicEncodedSize()
        if (packetIdentifier in validControlPacketIdentifierRange) size += UShort.SIZE_BYTES
        size += propertiesSectionSize()
        size += payloadSize()
        return size
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        val props: Collection<MqttProperty>? = properties.props.ifEmpty { null }
        if (qualityOfService == AT_MOST_ONCE) {
            PublishBodyV5Qos0Codec.encode(
                writeBuffer,
                PublishBodyV5Qos0(topic.toString(), props, payload),
            ) { buf, v -> codec.encode(buf, v) }
        } else {
            PublishBodyV5QosNonZeroCodec.encode(
                writeBuffer,
                PublishBodyV5QosNonZero(topic.toString(), packetIdentifier.toUShort(), props, payload),
            ) { buf, v -> codec.encode(buf, v) }
        }
    }

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): ControlPacket? =
        when (qualityOfService) {
            AT_LEAST_ONCE ->
                PublishAcknowledgment(
                    AckVariableHeader(packetIdentifier, reasonCode, AckProperties(reasonString, userProperty)),
                )
            QualityOfService.EXACTLY_ONCE ->
                PublishReceived(
                    AckVariableHeader(packetIdentifier, reasonCode, AckProperties(reasonString, userProperty)),
                )
            else -> null
        }

    override fun setDupFlagNewPubMessage(): PublishMessage =
        if (qualityOfService == AT_MOST_ONCE && dup) {
            PublishMessageV5(topic, qualityOfService, false, retain, packetIdentifier, properties, payload, codec)
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            PublishMessageV5(topic, qualityOfService, true, retain, packetIdentifier, properties, payload, codec)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            AT_LEAST_ONCE, QualityOfService.EXACTLY_ONCE ->
                PublishMessageV5(topic, qualityOfService, dup, retain, packetIdentifier, properties, payload, codec)
        }

    override fun validate(): MalformedPacketException? {
        if (qualityOfService == AT_MOST_ONCE &&
            packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-1] PUBLISH at QoS 0 MUST NOT contain a Packet Identifier.",
            )
        } else if (qualityOfService.isGreaterThan(AT_MOST_ONCE) &&
            packetIdentifier !in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-5] PUBLISH at QoS > 0 MUST contain a non-zero Packet Identifier.",
            )
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PublishMessageV5<*>) return false
        return topic == other.topic &&
            qualityOfService == other.qualityOfService &&
            dup == other.dup &&
            retain == other.retain &&
            packetIdentifier == other.packetIdentifier &&
            properties == other.properties &&
            payload == other.payload
    }

    override fun hashCode(): Int {
        var r = topic.hashCode()
        r = 31 * r + qualityOfService.hashCode()
        r = 31 * r + dup.hashCode()
        r = 31 * r + retain.hashCode()
        r = 31 * r + packetIdentifier
        r = 31 * r + properties.hashCode()
        r = 31 * r + (payload?.hashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "PublishMessageV5(topic=$topic, qos=$qualityOfService, dup=$dup, retain=$retain, " +
            "packetId=$packetIdentifier, properties=$properties)"

    /**
     * MQTT 5 PUBLISH variable-header properties (3.3.2.3).
     */
    data class Properties(
        val payloadFormatIndicator: Boolean = false,
        val messageExpiryInterval: Long? = null,
        val topicAlias: Int? = null,
        val responseTopic: TopicName? = null,
        val correlationData: ReadBuffer? = null,
        val userProperty: List<Pair<String, String>> = emptyList(),
        val subscriptionIdentifier: Set<Long> = emptySet(),
        val contentType: String? = null,
    ) {
        init {
            if (topicAlias == 0) {
                throw ProtocolError(
                    "Topic Alias not permitted to be set to 0:" +
                        "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413",
                )
            }
        }

        val props: List<MqttProperty> by lazy(LazyThreadSafetyMode.NONE) {
            buildList {
                if (payloadFormatIndicator) add(PayloadFormatIndicator(payloadFormatIndicator))
                if (messageExpiryInterval != null) add(MessageExpiryInterval(messageExpiryInterval.toUInt()))
                if (topicAlias != null) add(TopicAlias(topicAlias.toUShort()))
                if (responseTopic != null) add(ResponseTopic(responseTopic.toString()))
                if (correlationData != null) {
                    correlationData.position(0)
                    add(CorrelationData(correlationData.remaining().toUShort(), correlationData))
                }
                for (kv in userProperty) add(UserProperty(kv.first, kv.second))
                for (sub in subscriptionIdentifier) add(SubscriptionIdentifier(sub.toInt()))
                if (contentType != null) add(ContentType(contentType))
            }
        }

        companion object {
            fun from(keyValuePairs: Collection<MqttProperty>?): Properties {
                val p = PropertyExtractor(keyValuePairs, "PUBLISH")
                val payloadFormatIndicator = p.single<PayloadFormatIndicator>()?.isUtf8 ?: false
                val messageExpiryInterval = p.single<MessageExpiryInterval>()?.seconds?.toLong()
                val topicAlias =
                    p.single<TopicAlias>()
                        ?.also {
                            if (it.value == 0.toUShort()) {
                                throw ProtocolError(
                                    "Topic Alias not permitted to be set to 0:" +
                                        "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413",
                                )
                            }
                        }?.value?.toInt()
                val responseTopic = p.single<ResponseTopic>()?.let { TopicName.fromOrThrow(it.value) }
                val correlationData = p.single<CorrelationData<*>>()?.data as? ReadBuffer
                val userProperty = p.list<UserProperty>().map { it.key to it.value }
                val subscriptionIdentifier =
                    p.list<SubscriptionIdentifier>().mapTo(LinkedHashSet()) {
                        if (it.value == 0) {
                            throw ProtocolError(
                                "Subscription Identifier not permitted to be set to 0:" +
                                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477417",
                            )
                        }
                        it.value.toLong()
                    }
                val contentType = p.single<ContentType>()?.value
                p.rejectUnknown()
                return Properties(
                    payloadFormatIndicator,
                    messageExpiryInterval,
                    topicAlias,
                    responseTopic,
                    correlationData,
                    userProperty,
                    subscriptionIdentifier,
                    contentType,
                )
            }
        }
    }

    data class FixedHeader(
        val dup: Boolean = false,
        val qos: QualityOfService = AT_MOST_ONCE,
        val retain: Boolean = false,
    ) {
        companion object {
            fun fromByte(byte1: UByte): FixedHeader {
                val byte1Int = byte1.toInt()
                val dup = byte1Int.shl(4).toUByte().toInt().shr(7) == 1
                val qosBit2 = byte1Int.shl(5).toUByte().toInt().shr(7) == 1
                val qosBit1 = byte1Int.shl(6).toUByte().toInt().shr(7) == 1
                if (qosBit2 && qosBit1) {
                    throw MalformedPacketException(
                        "A PUBLISH Packet MUST NOT have both QoS bits set to 1 [MQTT-3.3.1-4].",
                    )
                }
                val qos = QualityOfService.fromBooleans(qosBit2, qosBit1)
                val retain = byte1Int.shl(7).toUByte().toInt().shr(7) == 1
                return FixedHeader(dup, qos, retain)
            }
        }
    }

    companion object {
        /**
         * Decode an incoming v5 PUBLISH. Returns `PublishMessageV5<ReadBuffer>` with a
         * zero-copy payload slice.
         *
         * `internal` because typed subscribers must go through `SubscriberEntry.Typed` for
         * decoding — this factory can only produce the raw-bytes variant. `@PublishedApi`
         * lets the inline `ControlPacketV5.fromTyped` dispatch reach it without widening
         * the source-level API.
         */
        @PublishedApi
        internal fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessageV5<ReadBuffer> {
            val fixed = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            return if (fixed.qos == AT_MOST_ONCE) {
                val body = PublishBodyV5Qos0Codec.decode(sliced) { pr -> readFullPayload(pr) }
                PublishMessageV5(
                    topic = TopicName.fromOrThrow(body.topic),
                    qualityOfService = fixed.qos,
                    dup = fixed.dup,
                    retain = fixed.retain,
                    packetIdentifier = NO_PACKET_ID,
                    properties = Properties.from(body.properties),
                    payload = body.payload,
                    codec = IdentityBufferCodec,
                )
            } else {
                val body = PublishBodyV5QosNonZeroCodec.decode(sliced) { pr -> readFullPayload(pr) }
                PublishMessageV5(
                    topic = TopicName.fromOrThrow(body.topic),
                    qualityOfService = fixed.qos,
                    dup = fixed.dup,
                    retain = fixed.retain,
                    packetIdentifier = body.packetIdentifier.toInt(),
                    properties = Properties.from(body.properties),
                    payload = body.payload,
                    codec = IdentityBufferCodec,
                )
            }
        }

        fun ofRaw(
            topic: TopicName,
            qos: QualityOfService = AT_MOST_ONCE,
            payload: ReadBuffer? = null,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
            properties: Properties = Properties(),
        ): PublishMessageV5<ReadBuffer> =
            PublishMessageV5(
                topic, qos, dup, retain, packetIdentifier, properties,
                payload ?: BufferFactory.Default.allocate(0),
                IdentityBufferCodec,
            )

        fun <P> ofTyped(
            topic: TopicName,
            qos: QualityOfService,
            payload: P,
            codec: PayloadCodec<P>,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
            properties: Properties = Properties(),
        ): PublishMessageV5<P> =
            PublishMessageV5(topic, qos, dup, retain, packetIdentifier, properties, payload, codec)

        private fun readFullPayload(pr: PayloadReader): ReadBuffer = pr.copyToBuffer()
    }
}
