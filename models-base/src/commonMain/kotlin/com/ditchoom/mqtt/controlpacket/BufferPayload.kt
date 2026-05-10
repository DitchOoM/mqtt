package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Zero-copy [Payload] wrapper around a [ReadBuffer] used by the per-packet codec layer
 * for opaque payload bytes (PUBLISH application payload, CONNECT will payload, v5 auth /
 * correlation data).
 *
 * Production code parameterizes the sealed `ControlPacketV4 / ControlPacketV5` parents
 * with `BufferPayload` so the generated dispatcher decodes payload windows as zero-copy
 * aliases. Consumers needing typed payloads can either:
 *  - Provide a custom `Codec<P>` to the codec class constructor (e.g.
 *    `ControlPacketV4Codec(MyPayloadCodec)`), or
 *  - Wrap raw bytes via `BufferPayload(buffer)` at construction sites.
 */
@JvmInline
value class BufferPayload(
    val buffer: ReadBuffer,
) : Payload

/**
 * Hand-written `Codec<BufferPayload>` for the per-packet payload slot. Decode aliases the
 * bounded payload window (zero-copy); encode writes the wrapped buffer's bytes to the
 * output. Wire size is the wrapped buffer's `remaining()`.
 */
object BufferPayloadCodec : Codec<BufferPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): BufferPayload = BufferPayload(buffer)

    override fun encode(
        buffer: WriteBuffer,
        value: BufferPayload,
        context: EncodeContext,
    ) {
        if (value.buffer.hasRemaining()) buffer.write(value.buffer)
    }

    override fun wireSize(
        value: BufferPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.buffer.remaining())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
