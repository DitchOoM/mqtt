package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
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
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange

/**
 * MQTT 3.1.1 PUBLISH packet.
 *
 * The user-facing payload API lives on the base [PublishMessage] class ([PublishMessage.usePayload]);
 * this subclass handles v4-specific wire encoding.
 */
class PublishMessageV4 internal constructor(
    topic: TopicName,
    qualityOfService: QualityOfService,
    dup: Boolean,
    retain: Boolean,
    packetIdentifier: Int,
    storage: PayloadStorage,
) : PublishMessage(topic, qualityOfService, dup, retain, packetIdentifier, storage), ControlPacketV4 {
    private fun variableHeaderSize(): Int {
        var size = UShort.SIZE_BYTES + topic.toString().utf8Length()
        if (packetIdentifier in validControlPacketIdentifierRange) size += UShort.SIZE_BYTES
        return size
    }

    private fun payloadSizeBytes(): Int =
        when (val s = storage) {
            is PayloadStorage.Bytes -> s.buffer?.remaining() ?: 0
            is PayloadStorage.Encode -> s.size()
        }

    override fun remainingLength(): Int = variableHeaderSize() + payloadSizeBytes()

    override fun encodeBody(writeBuffer: WriteBuffer) {
        writeBuffer.writeMqttUtf8String(topic.toString())
        if (packetIdentifier in validControlPacketIdentifierRange) {
            writeBuffer.writeUShort(packetIdentifier.toUShort())
        }
        when (val s = storage) {
            is PayloadStorage.Bytes -> s.buffer?.let { writeBuffer.write(it) }
            is PayloadStorage.Encode -> s.write(writeBuffer)
        }
    }

    override fun serialize(writeBuffer: WriteBuffer) {
        if (storage is PayloadStorage.Bytes) {
            super<ControlPacketV4>.serialize(writeBuffer)
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
            return super<ControlPacketV4>.serialize(factory)
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
            AT_LEAST_ONCE -> PublishAcknowledgment(packetIdentifier.toUShort())
            EXACTLY_ONCE -> PublishReceived(packetIdentifier.toUShort())
            else -> null
        }

    override fun setDupFlagNewPubMessage(): PublishMessage =
        if (qualityOfService == AT_MOST_ONCE && dup) {
            PublishMessageV4(topic, qualityOfService, false, retain, packetIdentifier, storage)
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            PublishMessageV4(topic, qualityOfService, true, retain, packetIdentifier, storage)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            AT_LEAST_ONCE, EXACTLY_ONCE ->
                PublishMessageV4(topic, qualityOfService, dup, retain, packetIdentifier, storage)
        }

    override fun validate(): MalformedPacketException? {
        if (qualityOfService == AT_MOST_ONCE &&
            packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-1] SUBSCRIBE, UNSUBSCRIBE, and PUBLISH (in cases where QoS > 0)" +
                    " Control Packets MUST contain a non-zero 16-bit Packet Identifier.",
            )
        } else if (qualityOfService.isGreaterThan(AT_MOST_ONCE) &&
            packetIdentifier !in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-5] A PUBLISH Packet MUST NOT contain a Packet Identifier if its QoS" +
                    " value is set to 0.",
            )
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PublishMessageV4) return false
        return topic == other.topic &&
            qualityOfService == other.qualityOfService &&
            dup == other.dup &&
            retain == other.retain &&
            packetIdentifier == other.packetIdentifier &&
            storageEquals(storage, other.storage)
    }

    override fun hashCode(): Int {
        var r = topic.hashCode()
        r = 31 * r + qualityOfService.hashCode()
        r = 31 * r + dup.hashCode()
        r = 31 * r + retain.hashCode()
        r = 31 * r + packetIdentifier
        return r
    }

    override fun toString(): String =
        "PublishMessageV4(topic=$topic, qos=$qualityOfService, dup=$dup, retain=$retain, " +
            "packetId=$packetIdentifier, payloadSize=${payloadSizeBytes()})"

    companion object {
        /**
         * Wire decode for incoming v4 PUBLISH. Uses zero-copy buffer slicing.
         *
         * The returned message's payload storage is a [PayloadStorage.Bytes] whose buffer is a
         * slice of [buffer] — the underlying bytes are NOT copied. If the caller needs to retain
         * payload bytes past the buffer's scope, do so inside a [PublishMessage.usePayload] block.
         */
        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessageV4 {
            val fixed = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            val (_, topicStr) = sliced.readMqttUtf8StringNotValidatedSized()
            val packetId =
                if (fixed.qos == AT_MOST_ONCE) NO_PACKET_ID
                else sliced.readUnsignedShort().toInt()
            val payload =
                if (sliced.remaining() > 0) sliced.readBytes(sliced.remaining()) else null
            return PublishMessageV4(
                topic = TopicName.fromOrThrow(topicStr),
                qualityOfService = fixed.qos,
                dup = fixed.dup,
                retain = fixed.retain,
                packetIdentifier = packetId,
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
        ): PublishMessageV4 =
            PublishMessageV4(topic, qos, dup, retain, packetIdentifier, PayloadStorage.Bytes(payload))

        fun <P> ofTyped(
            topic: TopicName,
            qos: QualityOfService,
            payload: P,
            encodePayload: (WriteBuffer, P) -> Unit,
            payloadSize: (P) -> Int,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
        ): PublishMessageV4 =
            PublishMessageV4(
                topic, qos, dup, retain, packetIdentifier,
                PayloadStorage.Encode(
                    write = { buf -> encodePayload(buf, payload) },
                    size = { payloadSize(payload) },
                ),
            )

        private fun storageEquals(a: PayloadStorage, b: PayloadStorage): Boolean =
            when {
                a is PayloadStorage.Bytes && b is PayloadStorage.Bytes -> a.buffer == b.buffer
                else -> a === b
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
}
