package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.MAX_FIXED_HEADER_SIZE
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PayloadStorage
import com.ditchoom.mqtt.controlpacket.PublishMessage
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
import com.ditchoom.mqtt5.controlpacket.properties.encodeMqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

/**
 * MQTT 5.0 PUBLISH packet.
 *
 * The user-facing payload API lives on the base [PublishMessage] class ([PublishMessage.usePayload]);
 * this subclass adds v5 [properties] and handles v5-specific wire encoding.
 */
class PublishMessageV5 internal constructor(
    topic: TopicName,
    qualityOfService: QualityOfService,
    dup: Boolean,
    retain: Boolean,
    packetIdentifier: Int,
    val properties: Properties,
    storage: PayloadStorage,
) : PublishMessage(topic, qualityOfService, dup, retain, packetIdentifier, storage), ControlPacketV5 {
    private fun variableHeaderSize(): Int {
        var size = UShort.SIZE_BYTES + topic.toString().utf8Length()
        if (packetIdentifier in validControlPacketIdentifierRange) size += UShort.SIZE_BYTES
        val propsSize = mqttPropertiesSize(properties.props)
        size += variableByteSize(propsSize) + propsSize
        return size
    }

    private fun payloadSizeBytes(): Int = payloadSize()

    override fun remainingLength(): Int = variableHeaderSize() + payloadSizeBytes()

    override fun encodeBody(writeBuffer: WriteBuffer) {
        writeBuffer.writeMqttUtf8String(topic.toString())
        if (packetIdentifier in validControlPacketIdentifierRange) {
            writeBuffer.writeUShort(packetIdentifier.toUShort())
        }
        // Properties section: VBI length + each encoded property
        val props = properties.props
        val propsSize = mqttPropertiesSize(props)
        writeBuffer.writeVariableByteInteger(propsSize)
        for (p in props) {
            writeBuffer.encodeMqttProperty<ReadBuffer, ReadBuffer>(
                p,
                encodeCorrelationData = { buf, data -> buf.write(data); data.resetForRead() },
                encodeAuthenticationData = { buf, data -> buf.write(data); data.resetForRead() },
            )
        }
        when (val s = storageBytesOrEncode()) {
            is PayloadStorage.Bytes -> s.buffer?.let { writeBuffer.write(it) }
            is PayloadStorage.Encode -> s.write(writeBuffer)
        }
    }

    private fun storageBytesOrEncode(): PayloadStorage = storage

    override fun serialize(writeBuffer: WriteBuffer) {
        if (storage is PayloadStorage.Bytes) {
            super<ControlPacketV5>.serialize(writeBuffer)
            return
        }
        // Backpatch path for typed payloads: reserve max fixed header, write body, patch byte1 + VBI.
        val start = writeBuffer.position()
        writeBuffer.position(start + MAX_FIXED_HEADER_SIZE)
        encodeBody(writeBuffer)
        val end = writeBuffer.position()
        val bodySize = end - start - MAX_FIXED_HEADER_SIZE
        val vbiSize = variableByteSize(bodySize).toInt()
        val actualStart = start + MAX_FIXED_HEADER_SIZE - 1 - vbiSize
        writeBuffer.set(actualStart, byte1.toByte())
        writeBuffer.position(actualStart + 1)
        writeBuffer.writeVariableByteInteger(bodySize)
        writeBuffer.position(end)
    }

    override fun serialize(factory: BufferFactory): ReadBuffer {
        if (storage is PayloadStorage.Bytes) {
            return super<ControlPacketV5>.serialize(factory)
        }
        val headerSize = variableHeaderSize() + MAX_FIXED_HEADER_SIZE
        val enc = storage as PayloadStorage.Encode
        val estimated = enc.size()
        var buf = factory.allocate(headerSize + estimated)
        try {
            serialize(buf)
        } catch (_: BufferOverflowException) {
            buf = factory.allocate((headerSize + estimated) * 2)
            serialize(buf)
        }
        val endPos = buf.position()
        val bodySize = endPos - MAX_FIXED_HEADER_SIZE
        val vbiSize = variableByteSize(bodySize).toInt()
        val actualStart = MAX_FIXED_HEADER_SIZE - 1 - vbiSize
        buf.position(actualStart)
        buf.setLimit(endPos)
        return buf.slice()
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
            PublishMessageV5(topic, qualityOfService, false, retain, packetIdentifier, properties, storage)
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            PublishMessageV5(topic, qualityOfService, true, retain, packetIdentifier, properties, storage)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            AT_LEAST_ONCE, QualityOfService.EXACTLY_ONCE ->
                PublishMessageV5(topic, qualityOfService, dup, retain, packetIdentifier, properties, storage)
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
        if (other !is PublishMessageV5) return false
        return topic == other.topic &&
            qualityOfService == other.qualityOfService &&
            dup == other.dup &&
            retain == other.retain &&
            packetIdentifier == other.packetIdentifier &&
            properties == other.properties
    }

    override fun hashCode(): Int {
        var r = topic.hashCode()
        r = 31 * r + qualityOfService.hashCode()
        r = 31 * r + dup.hashCode()
        r = 31 * r + retain.hashCode()
        r = 31 * r + packetIdentifier
        r = 31 * r + properties.hashCode()
        return r
    }

    override fun toString(): String =
        "PublishMessageV5(topic=$topic, qos=$qualityOfService, dup=$dup, retain=$retain, " +
            "packetId=$packetIdentifier, properties=$properties, payloadSize=${payloadSizeBytes()})"

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
         * Wire decode for incoming v5 PUBLISH. Uses zero-copy buffer slicing for the payload.
         */
        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessageV5 {
            val fixed = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            val (_, topicStr) = sliced.readMqttUtf8StringNotValidatedSized()
            val packetId =
                if (fixed.qos == AT_MOST_ONCE) NO_PACKET_ID
                else sliced.readUnsignedShort().toInt()
            val propsRaw = sliced.readProperties()
            val props = Properties.from(propsRaw)
            val payload =
                if (sliced.remaining() > 0) sliced.readBytes(sliced.remaining()) else null
            return PublishMessageV5(
                topic = TopicName.fromOrThrow(topicStr),
                qualityOfService = fixed.qos,
                dup = fixed.dup,
                retain = fixed.retain,
                packetIdentifier = packetId,
                properties = props,
                storage = PayloadStorage.Bytes(payload),
            )
        }

        fun ofRaw(
            topic: TopicName,
            qos: QualityOfService = AT_MOST_ONCE,
            payload: ReadBuffer? = null,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
            properties: Properties = Properties(),
        ): PublishMessageV5 =
            PublishMessageV5(topic, qos, dup, retain, packetIdentifier, properties, PayloadStorage.Bytes(payload))

        fun <P> ofTyped(
            topic: TopicName,
            qos: QualityOfService,
            payload: P,
            encodePayload: (WriteBuffer, P) -> Unit,
            payloadSize: (P) -> Int,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
            properties: Properties = Properties(),
        ): PublishMessageV5 =
            PublishMessageV5(
                topic, qos, dup, retain, packetIdentifier, properties,
                PayloadStorage.Encode(
                    write = { buf -> encodePayload(buf, payload) },
                    size = { payloadSize(payload) },
                ),
            )
    }
}
