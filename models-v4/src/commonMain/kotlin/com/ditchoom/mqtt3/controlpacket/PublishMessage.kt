package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.MAX_FIXED_HEADER_SIZE
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
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

@ProtocolMessage
data class PublishWithIdV4Body<@Payload P>(
    @LengthPrefixed val topicName: String,
    val packetId: UShort,
    @RemainingBytes val payload: P,
)

@ProtocolMessage
data class PublishNoIdV4Body<@Payload P>(
    @LengthPrefixed val topicName: String,
    @RemainingBytes val payload: P,
)

/**
 * A PUBLISH Control Packet is sent from a Client to a Server or from Server to a Client to transport an
 * Application Message.
 */
data class PublishMessage<P>(
    val fixed: FixedHeader = FixedHeader(),
    val variable: VariableHeader,
    override val payload: P,
    val encodePayload: ((WriteBuffer, P) -> Unit)? = null,
    val payloadSize: ((P) -> Int)? = null,
) : ControlPacketV4,
    IPublishMessage<P> {
    override val controlPacketValue: Byte get() = 3
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = fixed.flags

    override val packetIdentifier: Int = variable.packetIdentifier

    override val qualityOfService: QualityOfService = fixed.qos

    override fun encodeBody(writeBuffer: WriteBuffer) {
        val topicStr = variable.topicName.toString()
        val writer: (WriteBuffer, P) -> Unit = encodePayload ?: { buf, p ->
            if (p is ReadBuffer) buf.write(p)
        }
        if (fixed.qos == AT_MOST_ONCE) {
            PublishNoIdV4BodyCodec.encode(
                writeBuffer, PublishNoIdV4Body(topicStr, payload), writer,
            )
        } else {
            PublishWithIdV4BodyCodec.encode(
                writeBuffer,
                PublishWithIdV4Body(topicStr, variable.packetIdentifier.toUShort(), payload),
                writer,
            )
        }
    }

    override fun remainingLength(): Int {
        val payloadBytes = when {
            payload is ReadBuffer -> (payload as ReadBuffer).remaining()
            payload == null -> 0
            payloadSize != null -> payloadSize.invoke(payload)
            else -> error("remainingLength() unavailable for typed payload without payloadSize")
        }
        return variable.size() + payloadBytes
    }

    override fun packetSize(): Int {
        if (payload != null && payload !is ReadBuffer && payloadSize == null) {
            error("packetSize() unavailable for typed payload without payloadSize")
        }
        return super<ControlPacketV4>.packetSize()
    }

    override fun serialize(writeBuffer: WriteBuffer) {
        if (payload == null || payload is ReadBuffer) {
            super<ControlPacketV4>.serialize(writeBuffer)
            return
        }
        // Backpatch: reserve max fixed header, write body, patch byte1 + VBI
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
        if (payload == null || payload is ReadBuffer) {
            return super<ControlPacketV4>.serialize(factory)
        }
        // Backpatch path: allocate based on payloadSize hint, grow if needed
        val headerSize = variable.size() + MAX_FIXED_HEADER_SIZE
        val estimatedPayloadSize = payloadSize?.invoke(payload) ?: DEFAULT_PAYLOAD_HEADROOM
        var buf = factory.allocate(headerSize + estimatedPayloadSize)
        try {
            serialize(buf)
        } catch (_: BufferOverflowException) {
            // payloadSize underestimated — grow and retry from the start
            buf = factory.allocate((headerSize + estimatedPayloadSize) * 2)
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
    ): ControlPacket? = when (fixed.qos) {
        AT_LEAST_ONCE -> {
            PublishAcknowledgment(variable.packetIdentifier.toUShort())
        }

        EXACTLY_ONCE -> {
            PublishReceived(variable.packetIdentifier.toUShort())
        }

        else -> null
    }

    override fun setDupFlagNewPubMessage(): IPublishMessage<P> =
        if (fixed.qos == AT_MOST_ONCE && fixed.dup) {
            copy(fixed = fixed.copy(dup = false), variable = variable, payload = payload)
        } else if (fixed.qos != AT_MOST_ONCE && !fixed.dup) {
            copy(fixed = fixed.copy(dup = true), variable = variable, payload = payload)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): IPublishMessage<P> =
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

    companion object {
        /** Default headroom when no payloadSize function is provided. */
        private const val DEFAULT_PAYLOAD_HEADROOM = 4096

        operator fun invoke(
            topicName: String,
            qos: QualityOfService = AT_MOST_ONCE,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
            payload: ReadBuffer? = null,
        ): PublishMessage<ReadBuffer?> = PublishMessage(
            FixedHeader(dup, qos, retain),
            VariableHeader(TopicName.fromOrThrow(topicName), packetIdentifier),
            payload,
        )

        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessage<ReadBuffer?> {
            val fixedHeader = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            if (fixedHeader.qos == AT_MOST_ONCE) {
                val wire = PublishNoIdV4BodyCodec.decode<ReadBuffer?>(sliced) { pr ->
                    if (pr.remaining() > 0) pr.copyToBuffer() else null
                }
                return PublishMessage(
                    fixedHeader,
                    VariableHeader(TopicName.fromOrThrow(wire.topicName), NO_PACKET_ID),
                    wire.payload,
                )
            } else {
                val wire = PublishWithIdV4BodyCodec.decode<ReadBuffer?>(sliced) { pr ->
                    if (pr.remaining() > 0) pr.copyToBuffer() else null
                }
                return PublishMessage(
                    fixedHeader,
                    VariableHeader(TopicName.fromOrThrow(wire.topicName), wire.packetId.toInt()),
                    wire.payload,
                )
            }
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
        ): PublishMessage<ReadBuffer?> {
            val fixed = FixedHeader(dup, qos, retain)
            val variable = VariableHeader(topicName, packetIdentifier)
            return PublishMessage(fixed, variable, payload)
        }
    }
}
