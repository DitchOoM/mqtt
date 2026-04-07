package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange

/**
 * A PUBLISH Control Packet is sent from a Client to a Server or from Server to a Client to transport an
 * Application Message.
 */
data class PublishMessage(
    val fixed: FixedHeader = FixedHeader(),
    val variable: VariableHeader,
    override val payload: ReadBuffer? = null,
) : ControlPacketV4,
    IPublishMessage {
    override val controlPacketValue: Byte get() = 3
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = fixed.flags
    constructor(
        topicName: String,
        qos: QualityOfService,
        dup: Boolean = false,
        retain: Boolean = false,
        packetIdentifier: Int = NO_PACKET_ID,
        payload: ReadBuffer? = null,
    ) : this(
        FixedHeader(dup, qos, retain),
        VariableHeader(TopicName.fromOrThrow(topicName), packetIdentifier),
        payload,
    )

    constructor(
        topicName: TopicName,
        qos: QualityOfService,
        dup: Boolean = false,
        retain: Boolean = false,
        packetIdentifier: Int = NO_PACKET_ID,
        payload: ReadBuffer? = null,
    ) : this(
        FixedHeader(dup, qos, retain),
        VariableHeader(topicName, packetIdentifier),
        payload,
    )

    override val packetIdentifier: Int = variable.packetIdentifier

    override val qualityOfService: QualityOfService = fixed.qos

    override fun encodeBody(writeBuffer: WriteBuffer) {
        writeBuffer.writeMqttUtf8String(variable.topicName.toString())
        if (fixed.qos != AT_MOST_ONCE) {
            writeBuffer.writeUShort(variable.packetIdentifier.toUShort())
        }
        if (payload != null) {
            writeBuffer.write(payload)
        }
    }

    override fun remainingLength() = variable.size() + payloadSize()

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): ControlPacket? = when (fixed.qos) {
        AT_LEAST_ONCE -> {
            PublishAcknowledgment(variable.packetIdentifier.toUShort())
        }

        EXACTLY_ONCE -> {
            PublishReceived(variable.packetIdentifier.toUShort())
        }

        else -> null
    }

    override fun setDupFlagNewPubMessage(): IPublishMessage =
        if (fixed.qos == AT_MOST_ONCE && fixed.dup) {
            copy(fixed = fixed.copy(dup = false), variable = variable, payload = payload)
        } else if (fixed.qos != AT_MOST_ONCE && !fixed.dup) {
            copy(fixed = fixed.copy(dup = true), variable = variable, payload = payload)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): IPublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            AT_LEAST_ONCE,
            EXACTLY_ONCE,
            -> copy(variable = variable.copy(packetIdentifier = packetIdentifier))
        }

    override val topic: TopicName = variable.topicName

    override fun validate(): MalformedPacketException? {
        if (fixed.qos == AT_MOST_ONCE &&
            variable.packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-1] SUBSCRIBE, UNSUBSCRIBE, and PUBLISH (in cases where QoS > 0)" +
                    " Control Packets MUST contain a non-zero 16-bit Packet Identifier.",
            )
        } else if (fixed.qos.isGreaterThan(AT_MOST_ONCE) &&
            variable.packetIdentifier !in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-5] A PUBLISH Packet MUST NOT contain a Packet Identifier if its QoS" +
                    " value is set to 0.",
            )
        }
        return null
    }

    data class FixedHeader(
        val dup: Boolean = false,
        val qos: QualityOfService = AT_MOST_ONCE,
        val retain: Boolean = false,
    ) {
        val flags by lazy(LazyThreadSafetyMode.NONE) {
            val dupInt = if (dup) 0b1000 else 0b0
            val qosInt = qos.integerValue.toInt().shl(1)
            val retainInt = if (retain) 0b1 else 0b0
            (dupInt or qosInt or retainInt).toByte()
        }

        companion object {
            fun fromByte(byte1: UByte): FixedHeader {
                val byte1Int = byte1.toInt()
                val dup = byte1Int.shl(4).toUByte().toInt().shr(7) == 1
                val qosBit2 = byte1Int.shl(5).toUByte().toInt().shr(7) == 1
                val qosBit1 = byte1Int.shl(6).toUByte().toInt().shr(7) == 1
                if (qosBit2 && qosBit1) {
                    throw MalformedPacketException(
                        "A PUBLISH Packet MUST NOT have both QoS bits set to 1 [MQTT-3.3.1-4]." +
                            " If a Server or Client receives a PUBLISH packet which has both " +
                            "QoS bits set to 1 it is a  Malformed Packet. Use DISCONNECT with" +
                            " Reason Code 0x81 (Malformed Packet) as described in section 4.13",
                    )
                }
                val qos = QualityOfService.fromBooleans(qosBit2, qosBit1)
                val retain = byte1Int.shl(7).toUByte().toInt().shr(7) == 1
                return FixedHeader(dup, qos, retain)
            }
        }
    }

    data class VariableHeader(
        val topicName: TopicName,
        val packetIdentifier: Int = NO_PACKET_ID,
    ) {
        fun size(): Int {
            var size = topicName.toString().utf8Length() + UShort.SIZE_BYTES
            if (packetIdentifier in validControlPacketIdentifierRange) {
                size += 2
            }
            return size
        }
    }

    private fun payloadSize(): Int = payload?.remaining() ?: 0

    companion object {
        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessage {
            val fixedHeader = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            val topicStr = sliced.readMqttUtf8StringNotValidatedSized().second
            val topicName = TopicName.fromOrThrow(topicStr)
            val packetId = if (fixedHeader.qos != AT_MOST_ONCE) sliced.readUnsignedShort().toInt() else NO_PACKET_ID
            val payload = if (sliced.remaining() > 0) sliced.readBytes(sliced.remaining()) else null
            return PublishMessage(fixedHeader, VariableHeader(topicName, packetId), payload)
        }

        fun build(
            dup: Boolean = false,
            qos: QualityOfService = AT_MOST_ONCE,
            retain: Boolean = false,
            topicName: TopicName,
            packetIdentifier: Int = NO_PACKET_ID,
        ) = buildPayload(dup, qos, retain, topicName, packetIdentifier)

        fun buildPayload(
            dup: Boolean = false,
            qos: QualityOfService = AT_MOST_ONCE,
            retain: Boolean = false,
            topicName: TopicName,
            packetIdentifier: Int = NO_PACKET_ID,
            payload: PlatformBuffer? = null,
        ): PublishMessage {
            val fixed = FixedHeader(dup, qos, retain)
            val variable = VariableHeader(topicName, packetIdentifier)
            return PublishMessage(fixed, variable, payload)
        }
    }
}
