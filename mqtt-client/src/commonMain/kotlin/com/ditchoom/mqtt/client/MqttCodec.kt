package com.ditchoom.mqtt.client

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.mqtt.MissingCodecException
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.MqttRemainingLengthCodec
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Codec
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Codec

/**
 * Binary [Codec] sitting between the framing layer and the typed control-packet
 * machinery.
 *
 * **Decode**: routes through the generated `decodeAggregating` companion so a
 * per-PUBLISH topic-router lambda can supply the consumer's payload codec
 * while the wire frame is still alive — zero-copy when the codec is
 * Pattern #1 (typed value from native handle). The lambda inspects
 * `partial.topicName` (decoded before the payload bytes are read) and
 * resolves the codec via [publishCodecForTopic]; missing-codec falls back
 * to [defaultPublishCodec] when supplied, otherwise throws
 * [MissingCodecException].
 *
 * **Encode**: routes through `ControlPacket.serialize` so non-PUBLISH packets
 * (CONNECT, SUBSCRIBE, PING…) reach the wire unchanged. PUBLISH encode is
 * handled per-call at `MqttClient.publish<P>(...)` which builds the
 * `PublishMessageV4<P>` / `ControlPacketV5.Publish<P>` with the caller's
 * codec — by the time the message reaches this serialize call, its
 * payload has already been encoded into bytes.
 *
 * **peekFrameSize**: same `[byte1][VBI(remainingLength)][body]` walker as
 * before — wire framing is identical between v4 and v5.
 */
class MqttCodec(
    private val factory: ControlPacketFactory,
    private val publishCodecForTopic: (topicName: String) -> Codec<out Payload>? = { null },
    private val defaultPublishCodec: Codec<out Payload>? = null,
) : Codec<ControlPacket> {
    @Suppress("UNCHECKED_CAST")
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ControlPacket =
        when (val version = factory.protocolVersion) {
            4 ->
                ControlPacketV4Codec.decodeAggregating<Payload>(
                    buffer = buffer,
                    context = context,
                    onPublishMessageV4 = { partial ->
                        val codec =
                            publishCodecForTopic(partial.topicName)
                                ?: defaultPublishCodec
                                ?: throw MissingCodecException(partial.topicName)
                        partial.complete(codec as Codec<Payload>)
                    },
                )
            5 ->
                ControlPacketV5Codec.decodeAggregating<Payload>(
                    buffer = buffer,
                    context = context,
                    onPublish = { partial ->
                        val codec =
                            publishCodecForTopic(partial.topicName)
                                ?: defaultPublishCodec
                                ?: throw MissingCodecException(partial.topicName)
                        partial.complete(codec as Codec<Payload>)
                    },
                )
            else -> error("Unsupported MQTT protocol version: $version (expected 4 or 5)")
        }

    override fun encode(
        buffer: WriteBuffer,
        value: ControlPacket,
        context: EncodeContext,
    ) = value.serialize(buffer)

    /**
     * Wire framing is `[byte1][VBI(remainingLength)][body]` for both MQTT v4 and v5 —
     * peek the VBI here directly rather than dispatching through a per-version
     * generated codec instance (which is parameterized by payload type and not
     * statically callable without a codec).
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
