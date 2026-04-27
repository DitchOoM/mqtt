package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BodyLengthFraming
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.variableByteSizeInt
import com.ditchoom.buffer.writeVariableByteInteger
import com.ditchoom.mqtt.MalformedPacketException
import kotlin.jvm.JvmInline

/**
 * MQTT fixed-header byte: top nibble = packet type, bottom nibble = packet-specific flags.
 * Used as the `@DispatchOn` discriminator for the v4 and v5 sealed control-packet trees.
 *
 * The flag-extraction helpers cover every packet type's reserved-vs-meaningful nibble:
 * PUBLISH uses dup/qos/retain; PUBREL/SUBSCRIBE/UNSUBSCRIBE pin the low nibble to `0010`;
 * other packet types pin it to `0000`. The processor enforces those reserved values via
 * each variant's `@PacketType(wire = …)` literal.
 *
 * The companion implements [BodyLengthFraming]: every MQTT control packet is framed
 * `[byte1][VBI(remainingLength)][body]`. The generated dispatcher consumes the framing
 * via the companion's `readBodyLength` / `writeBodyLength` / `peekFrameSize` /
 * `bodyLengthSize` calls.
 */
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    init {
        if (packetType == 3 && (raw.toInt() and 0b110) == 0b110) {
            throw MalformedPacketException(
                "PUBLISH QoS = 3 is malformed (both QoS bits set); only QoS 0/1/2 are valid.",
            )
        }
    }

    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val flags: Int get() = raw.toInt() and 0x0F

    val publishDup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val publishQos: Int get() = (raw.toInt() shr 1) and 0x3
    val publishRetain: Boolean get() = raw.toInt() and 1 == 1
    val publishHasPacketIdentifier: Boolean get() = publishQos > 0

    companion object : BodyLengthFraming<MqttFixedHeader> {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult {
            // Need at least byte1 + 1 VBI byte to compute frame size.
            if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
            var width = 0
            var len = 0
            var multiplier = 1
            while (width < 4) {
                if (stream.available() < baseOffset + 1 + width + 1) return PeekResult.NeedsMoreData
                val byte = stream.peekByte(baseOffset + 1 + width).toInt() and 0xFF
                len += (byte and 0x7F) * multiplier
                multiplier *= 128
                width += 1
                if ((byte and 0x80) == 0) return PeekResult.Size(1 + width + len)
            }
            return PeekResult.NeedsMoreData
        }

        override fun readBodyLength(buffer: ReadBuffer): Int = buffer.readVariableByteInteger()

        override fun writeBodyLength(
            buffer: WriteBuffer,
            n: Int,
        ) {
            buffer.writeVariableByteInteger(n)
        }

        override fun bodyLengthSize(n: Int): Int = variableByteSizeInt(n)
    }
}
