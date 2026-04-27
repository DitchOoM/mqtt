package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Codec
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Codec

class MqttCodec(
    private val factory: ControlPacketFactory,
) : Codec<ControlPacket> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ControlPacket = factory.from(buffer)

    override fun encode(
        buffer: WriteBuffer,
        value: ControlPacket,
        context: EncodeContext,
    ) = value.serialize(buffer)

    /**
     * Delegates to the per-version generated codec's `peekFrameSize`, which itself
     * delegates to [com.ditchoom.mqtt.controlpacket.MqttFixedHeader]'s
     * [com.ditchoom.buffer.codec.DispatchFraming] companion. The framing is
     * `[byte1][VBI(remainingLength)][body]` for both v4 and v5.
     */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult =
        when (factory.protocolVersion) {
            4 -> ControlPacketV4Codec.peekFrameSize(stream, baseOffset)
            5 -> ControlPacketV5Codec.peekFrameSize(stream, baseOffset)
            else -> error("Unsupported MQTT protocol version: ${factory.protocolVersion}")
        }
}
