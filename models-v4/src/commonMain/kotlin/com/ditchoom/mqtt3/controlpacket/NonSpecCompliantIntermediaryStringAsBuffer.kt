package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.Charset
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
 * **TEMPORARY — buffer-v1 Phase A placeholder.**
 *
 * Will be replaced in Phase B by one of:
 *  - multi-param parent threading `ControlPacketV4<W : Payload, PWD : Payload, P : Payload>`
 *    so consumers supply their own typed Will / Password / Publish payload codecs;
 *  - a field-level `@InjectedCodec` annotation on the wire-shape fields;
 *  - a hand-written `ConnectionRequestCodec` per variant.
 *
 * Until then PUBLISH payloads + Will payloads are intermediated through this type, which
 * is **non-spec-compliant**: MQTT v3.1.1 §3.3.2 (PUBLISH application bytes) and §3.1.3.3
 * (Will Message) are arbitrary bytes — UTF-8 decode is lossy for non-UTF-8 payloads. The
 * type name is intentionally long and ugly so every call site that uses it surfaces the
 * fix-it-later signal in code review.
 *
 * The type satisfies the buffer-v1 transitive Payload-shape rule because the only
 * transitively-walked property is [s], a `String` — strings are typed values with no
 * intermediate buffer reference and are allowed in `Payload`-implementing types.
 */
@JvmInline
value class NonSpecCompliantIntermediaryStringAsBuffer(
    val s: String,
) : Payload

/**
 * Codec for [NonSpecCompliantIntermediaryStringAsBuffer]. UTF-8 decodes the
 * `@RemainingBytes` slice into a `String`; encodes the string as UTF-8. Wire size
 * walks the codepoints (`String.writeUtf8(...)` semantics).
 */
object NonSpecCompliantIntermediaryStringAsBufferCodec : Codec<NonSpecCompliantIntermediaryStringAsBuffer> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): NonSpecCompliantIntermediaryStringAsBuffer =
        NonSpecCompliantIntermediaryStringAsBuffer(buffer.readString(buffer.remaining(), Charset.UTF8))

    override fun encode(
        buffer: WriteBuffer,
        value: NonSpecCompliantIntermediaryStringAsBuffer,
        context: EncodeContext,
    ) {
        buffer.writeString(value.s, Charset.UTF8)
    }

    // BackPatch: UTF-8 byte count requires a string walk; the framework back-patches the
    // outer length prefix on emit. Matches the `@LengthPrefixed val: String` shape.
    override fun wireSize(
        value: NonSpecCompliantIntermediaryStringAsBuffer,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
