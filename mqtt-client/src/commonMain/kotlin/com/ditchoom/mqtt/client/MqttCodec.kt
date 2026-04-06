package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.SizeEstimate
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

class MqttCodec(
    private val factory: ControlPacketFactory,
) : Codec<ControlPacket> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): ControlPacket = factory.from(buffer)

    override fun encode(
        buffer: WriteBuffer,
        value: ControlPacket,
        context: EncodeContext,
    ) = value.serialize(buffer)

    override fun sizeOf(value: ControlPacket): SizeEstimate = SizeEstimate.Exact(value.packetSize())
}

/**
 * MQTT frame boundary detection for [com.ditchoom.socket.transport.CodecConnection].
 *
 * Peeks at the fixed header (byte1 + variable-byte-integer remaining length)
 * to determine the total frame size without consuming any bytes.
 *
 * @return total frame size in bytes, or `null` if not enough data is buffered yet.
 */
fun mqttPeekFrameSize(
    stream: StreamProcessor,
    baseOffset: Int,
): Int? {
    if (stream.available() < baseOffset + 2) return null // need at least byte1 + 1 VBI byte
    var offset = baseOffset + 1 // skip byte1
    var multiplier = 1
    var value = 0
    for (i in 0 until 4) {
        if (stream.available() <= offset) return null
        val byte = stream.peekByte(offset).toInt() and 0xFF
        offset++
        value += (byte and 0x7F) * multiplier
        multiplier *= 128
        if (byte and 0x80 == 0) {
            return offset - baseOffset + value // 1 (byte1) + VBI bytes + remaining length
        }
    }
    return null // malformed VBI (>4 continuation bytes)
}
