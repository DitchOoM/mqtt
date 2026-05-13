package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.OpaqueBytesHandle
import com.ditchoom.buffer.codec.OpaqueBytesHandleCodec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.buffer.codec.handleEquals
import com.ditchoom.buffer.codec.handleHashCode
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Spec-compliant `Payload`-marker carrier for opaque PUBLISH application bytes.
 *
 * `OpaqueBytesHandle` (in `buffer-codec`) is the platform-shielded `PlatformBuffer`
 * carrier — it intentionally does NOT implement [Payload], so that buffer-codec
 * primitives stay decoupled from any protocol's Payload-shape marker. This thin
 * mqtt-side wrapper supplies the [Payload] marker the typed control-packet machinery
 * (`PublishMessageV4<P : Payload>`, `ControlPacketV5.Publish<P : Payload>`) requires.
 *
 * Used in three places under the buffer-v1 B-4 design:
 *  - `ControlPacketV{4,5}Factory.publish(...)` wraps the consumer's `ReadBuffer`
 *    payload into this type for downstream `PublishMessage` construction;
 *  - the sealed parents' default `serialize(...)` overrides use
 *    [OpaquePublishPayloadCodec] as the wire-encode payload codec, so messages with
 *    P bound to this type round-trip exactly (no UTF-8 loss);
 *  - `MqttCodec`'s missing-codec fallback (when no per-topic codec is registered and
 *    `defaultPublishCodec` is set) hands [OpaquePublishPayloadCodec] to the
 *    aggregator's `Partial.complete(...)`.
 *
 * Spec-compliant (unlike the Phase A
 * `NonSpecCompliantIntermediaryStringAsBuffer` UTF-8-lossy intermediary): MQTT
 * §3.3.2 PUBLISH application bytes are arbitrary octets, and this carrier preserves
 * them exactly through the consumer-owned `PlatformBuffer` inside [handle].
 *
 * Plain `class` (not `value class` / `data class`) to allow `equals` / `hashCode`
 * overrides that route through [OpaqueBytesHandle.handleEquals] for byte-content
 * comparison. [OpaqueBytesHandle] is an expect class whose actuals don't override
 * `equals` / `hashCode` (they hold an internal `PlatformBuffer` field that the KSP
 * walker treats as opaque), so default reference equality would surprise consumers
 * round-tripping a `PublishMessage` through encode/decode.
 */
class OpaquePublishPayload(
    val handle: OpaqueBytesHandle,
) : Payload {
    override fun equals(other: Any?): Boolean = other is OpaquePublishPayload && handle.handleEquals(other.handle)

    override fun hashCode(): Int = handle.handleHashCode()

    override fun toString(): String = "OpaquePublishPayload(byteSize=${handle.byteSize()})"

    /** Byte count carried by this payload. */
    fun byteSize(): Int = handle.byteSize()
}

/**
 * Codec for [OpaquePublishPayload]. Delegates to [OpaqueBytesHandleCodec] for the
 * buffer-allocation-and-copy decode (Pattern #2: consumer-owned `PlatformBuffer` via
 * `factory.allocate(...) + dst.write(source)`), and writes the carrier's owned bytes
 * back to the wire on encode.
 */
object OpaquePublishPayloadCodec : Codec<OpaquePublishPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): OpaquePublishPayload = OpaquePublishPayload(OpaqueBytesHandleCodec.decode(buffer, context))

    override fun encode(
        buffer: WriteBuffer,
        value: OpaquePublishPayload,
        context: EncodeContext,
    ) {
        buffer.write(value.handle.asReadBuffer())
    }

    override fun wireSize(
        value: OpaquePublishPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.handle.byteSize())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
