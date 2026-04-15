package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.codec.IdentityBufferCodec
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.PublishMessagePayloadMaterializer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange

/**
 * MQTT 3.1.1 PUBLISH packet, parameterized on the payload type [P].
 *
 * The payload is supplied via a [Codec] — see [ofRaw] for the `P = ReadBuffer` convenience
 * and [ofTyped] for an arbitrary typed payload. Wire encoding delegates to the generated
 * [PublishBodyV4Qos0Codec] / [PublishBodyV4QosNonZeroCodec] body codecs, which in turn
 * delegate the payload portion back to the supplied [Codec].
 *
 * Note: the [codec] is stored on the instance so that [ControlPacket.serialize] (which has
 * no codec parameter) can invoke it. See the Phase 1 commit message for the rationale.
 */
class PublishMessageV4<P> internal constructor(
    override val topic: TopicName,
    override val qualityOfService: QualityOfService,
    override val dup: Boolean,
    override val retain: Boolean,
    packetIdentifier: Int,
    override val payload: P,
    override val codec: PayloadCodec<P>,
) : PublishMessage, ControlPacketV4, PublishMessagePayloadMaterializer<P> {
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

    override fun remainingLength(): Int {
        var size = topicEncodedSize()
        if (packetIdentifier in validControlPacketIdentifierRange) size += UShort.SIZE_BYTES
        size += payloadSize()
        return size
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        if (qualityOfService == AT_MOST_ONCE) {
            PublishBodyV4Qos0Codec.encode(
                writeBuffer,
                PublishBodyV4Qos0(topic.toString(), payload),
            ) { buf, v -> codec.encode(buf, v) }
        } else {
            PublishBodyV4QosNonZeroCodec.encode(
                writeBuffer,
                PublishBodyV4QosNonZero(topic.toString(), packetIdentifier.toUShort(), payload),
            ) { buf, v -> codec.encode(buf, v) }
        }
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
            PublishMessageV4(topic, qualityOfService, false, retain, packetIdentifier, payload, codec)
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            PublishMessageV4(topic, qualityOfService, true, retain, packetIdentifier, payload, codec)
        } else {
            this
        }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            AT_LEAST_ONCE, EXACTLY_ONCE ->
                PublishMessageV4(topic, qualityOfService, dup, retain, packetIdentifier, payload, codec)
        }

    override fun validate(): MalformedPacketException? {
        if (qualityOfService == AT_MOST_ONCE &&
            packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-5] A PUBLISH Packet MUST NOT contain a Packet Identifier if its QoS" +
                    " value is set to 0.",
            )
        } else if (qualityOfService.isGreaterThan(AT_MOST_ONCE) &&
            packetIdentifier !in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-1] SUBSCRIBE, UNSUBSCRIBE, and PUBLISH (in cases where QoS > 0)" +
                    " Control Packets MUST contain a non-zero 16-bit Packet Identifier.",
            )
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PublishMessageV4<*>) return false
        return topic == other.topic &&
            qualityOfService == other.qualityOfService &&
            dup == other.dup &&
            retain == other.retain &&
            packetIdentifier == other.packetIdentifier &&
            payload == other.payload
    }

    override fun hashCode(): Int {
        var r = topic.hashCode()
        r = 31 * r + qualityOfService.hashCode()
        r = 31 * r + dup.hashCode()
        r = 31 * r + retain.hashCode()
        r = 31 * r + packetIdentifier
        r = 31 * r + (payload?.hashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "PublishMessageV4(topic=$topic, qos=$qualityOfService, dup=$dup, retain=$retain, " +
            "packetId=$packetIdentifier)"

    companion object {
        /**
         * Decode an incoming v4 PUBLISH from its fixed-header byte1 and VBI remaining-length.
         * Returns `PublishMessageV4<ReadBuffer>` — the payload is a zero-copy slice of [buffer].
         *
         * `internal` because typed subscribers must go through `SubscriberEntry.Typed` for
         * decoding — this factory can only produce the raw-bytes variant. `@PublishedApi`
         * lets the inline `ControlPacketV4.fromTyped` dispatch reach it without widening
         * the source-level API.
         */
        @PublishedApi
        internal fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): PublishMessageV4<ReadBuffer> {
            val fixed = FixedHeader.fromByte(byte1)
            val sliced = buffer.readBytes(remainingLength)
            return if (fixed.qos == AT_MOST_ONCE) {
                val body = PublishBodyV4Qos0Codec.decode(sliced) { pr -> readFullPayload(pr) }
                PublishMessageV4(
                    topic = TopicName.fromOrThrow(body.topic),
                    qualityOfService = fixed.qos,
                    dup = fixed.dup,
                    retain = fixed.retain,
                    packetIdentifier = NO_PACKET_ID,
                    payload = body.payload,
                    codec = IdentityBufferCodec,
                )
            } else {
                val body = PublishBodyV4QosNonZeroCodec.decode(sliced) { pr -> readFullPayload(pr) }
                PublishMessageV4(
                    topic = TopicName.fromOrThrow(body.topic),
                    qualityOfService = fixed.qos,
                    dup = fixed.dup,
                    retain = fixed.retain,
                    packetIdentifier = body.packetIdentifier.toInt(),
                    payload = body.payload,
                    codec = IdentityBufferCodec,
                )
            }
        }

        /**
         * Create a v4 PUBLISH with a raw-bytes payload (`P = ReadBuffer`).
         *
         * Note: null payload is represented as an empty [ReadBuffer] via the caller — if the
         * legacy nullable-payload shape is required, use the other overload and pass
         * a caller-allocated empty buffer.
         */
        fun ofRaw(
            topic: TopicName,
            qos: QualityOfService = AT_MOST_ONCE,
            payload: ReadBuffer? = null,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
        ): PublishMessageV4<ReadBuffer> =
            PublishMessageV4(
                topic, qos, dup, retain, packetIdentifier,
                payload ?: BufferFactory.Default.allocate(0),
                IdentityBufferCodec,
            )

        /**
         * Create a v4 PUBLISH with a typed payload. The [codec] is invoked during wire encoding
         * to write the payload bytes directly into the frame buffer (zero intermediate copy
         * if the codec implementation doesn't allocate internally).
         */
        fun <P> ofTyped(
            topic: TopicName,
            qos: QualityOfService,
            payload: P,
            codec: PayloadCodec<P>,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
        ): PublishMessageV4<P> =
            PublishMessageV4(topic, qos, dup, retain, packetIdentifier, payload, codec)

        private fun readFullPayload(pr: PayloadReader): ReadBuffer {
            // PayloadReader is a scoped view; materialize into a caller-owned ReadBuffer so the
            // resulting message remains usable after the codec releases the reader.
            return pr.copyToBuffer()
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

