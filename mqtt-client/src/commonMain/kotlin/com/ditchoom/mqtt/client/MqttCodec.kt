package com.ditchoom.mqtt.client

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.MqttRemainingLengthCodec

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
     * Wire framing is `[byte1][VBI(remainingLength)][body]` for both MQTT v4 and v5 —
     * so we peek the variable-byte-integer length directly here rather than dispatching
     * through a per-version generated codec instance (which is now parameterized by
     * payload type and not statically callable).
     */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
        val framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
        try {
            val start = framingPeek.position()
            val length =
                try {
                    MqttRemainingLengthCodec.decode(framingPeek, DecodeContext.Empty)
                } catch (e: Throwable) {
                    when (e::class.simpleName) {
                        "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" ->
                            return PeekResult.NeedsMoreData
                        else -> throw e
                    }
                }
            val width = framingPeek.position() - start
            val total = 1 + width + length.toInt()
            return if (stream.available() - baseOffset >= total) PeekResult.Complete(total) else PeekResult.NeedsMoreData
        } finally {
            (framingPeek as? PlatformBuffer)?.freeNativeMemory()
        }
    }
}
