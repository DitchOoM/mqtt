package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.mappingMalformedWire

/**
 * Test convenience replacing the retired `ControlPacketV4.from(buffer)` companion (the
 * `.from(buffer)` overloads were deleted). Tests that don't care about the PUBLISH payload
 * type (PUBACK, SUBACK, CONNECT, etc.) route through this helper.
 *
 * PUBLISH application bytes are decoded as [OpaquePublishPayload] (Pattern #2 — consumer-
 * owned `PlatformBuffer`) — spec-compliant, no UTF-8 loss. Tests asserting payload content
 * compare via `OpaquePublishPayload.handle.handleEquals(...)` or by re-decoding the bytes
 * through a specific codec.
 *
 * Preserves `from()`'s `DecodeException → MalformedPacketException` remap so the
 * `assertFailsWith<MalformedPacketException>` sites keep passing. Its narrow catch differs from
 * production's [com.ditchoom.mqtt.mappingMalformedWire] (which also remaps
 * `IllegalArgumentException` / the buffer-underflow / charset families) — [decodeProductionV4]
 * is the faithful production mirror.
 */
internal fun decodeV4(buffer: ReadBuffer): ControlPacketV4<OpaquePublishPayload> =
    try {
        ControlPacketV4Codec(OpaquePublishPayloadCodec).decode(buffer, DecodeContext.Empty)
    } catch (e: DecodeException) {
        throw MalformedPacketException(e.message ?: "malformed control packet")
    }

/**
 * Production-mirroring decode: routes through the generated `decodeAggregating`
 * companion **exactly** as `MqttCodec.decode` does — the PUBLISH branch goes through
 * the partial-then-`complete` aggregation path (not the instance-codec `publishCodec.decode`
 * that [decodeV4] uses). The two paths differ (the aggregation path skips the
 * instance codec's post-body `@FramedBy` consumption check), so the fuzzers target
 * **this** helper to harden the real production wire-decode boundary
 * (`ConnectivityManager.receive → MqttCodec.decode → decodeAggregating`). PUBLISH
 * application bytes are carried in [OpaquePublishPayload] (Pattern #2, byte-exact),
 * matching `MqttCodec`'s missing-codec fallback router.
 *
 * Like `MqttCodec.decode`, it wraps the raw malformed-wire families into
 * [com.ditchoom.mqtt.MalformedPacketException] via [com.ditchoom.mqtt.mappingMalformedWire]
 * (DitchOoM/mqtt#13), so malformed input surfaces here as an [com.ditchoom.mqtt.MqttException]
 * exactly as production does; genuine decoder bugs still propagate unwrapped.
 */
internal fun decodeProductionV4(buffer: ReadBuffer): ControlPacketV4<OpaquePublishPayload> =
    mappingMalformedWire {
        ControlPacketV4Codec.decodeAggregating(
            buffer = buffer,
            context = DecodeContext.Empty,
            onPublishMessageV4 = { it.complete(OpaquePublishPayloadCodec) },
        )
    }

/**
 * Test convenience replacing the now-gone `ControlPacket.serialize(WriteBuffer)` API.
 * Routes the variant through the v4 sealed-tree codec. PUBLISH payloads are intermediated
 * through [OpaquePublishPayload] for tests.
 *
 * The generated `ControlPacketV4Codec.encode(value, ctx, factory)` returns a freshly-
 * allocated `ReadBuffer` containing the framed bytes; we copy them into the caller's
 * buffer so tests that pre-allocate a writer continue to work.
 */
@Suppress("UNCHECKED_CAST")
internal fun serializeV4(
    packet: ControlPacketV4<*>,
    buffer: WriteBuffer,
) {
    val encoded =
        ControlPacketV4Codec(OpaquePublishPayloadCodec).encode(
            packet as ControlPacketV4<OpaquePublishPayload>,
            EncodeContext.Empty,
            BufferFactory.Default,
        )
    buffer.write(encoded)
}

/**
 * Test convenience replacing `ControlPacket.packetSize()`. Walks the variant's encoded
 * byte count by encoding to a scratch buffer and reading its position.
 */
@Suppress("UNCHECKED_CAST")
internal fun packetSizeV4(packet: ControlPacketV4<*>): Int {
    val encoded =
        ControlPacketV4Codec(OpaquePublishPayloadCodec).encode(
            packet as ControlPacketV4<OpaquePublishPayload>,
            EncodeContext.Empty,
            BufferFactory.Default,
        )
    return encoded.remaining()
}

/**
 * Allocate a write buffer + return the bytes back as a ReadBuffer after a roundtrip
 * encode of [packet]. Convenience for the common "encode-then-decode" test pattern.
 */
@Suppress("UNCHECKED_CAST")
internal fun encodeToReadBuffer(packet: ControlPacketV4<*>): ReadBuffer =
    ControlPacketV4Codec(OpaquePublishPayloadCodec).encode(
        packet as ControlPacketV4<OpaquePublishPayload>,
        EncodeContext.Empty,
        BufferFactory.Default,
    )

/**
 * Test convenience: build an [OpaquePublishPayload] from a UTF-8 string. Existing tests
 * that supplied a `payloadString` to the old `NonSpecCompliantIntermediaryStringAsBuffer`
 * payload migrate to this helper to preserve assertion semantics.
 */
internal fun opaquePublishPayloadOf(s: String): OpaquePublishPayload {
    // OwnedBytesHandle.byteSize() returns the wrapped buffer's full `capacity`, so we
    // must allocate exactly the UTF-8 byte count (not s.length * 4 max-width).
    val bytes = s.encodeToByteArray()
    val buf = BufferFactory.Default.allocate(bytes.size)
    buf.writeBytes(bytes)
    buf.resetForRead()
    return OpaquePublishPayload(ownedBytesFrom(buf))
}

/**
 * Test convenience: materialize an [OpaquePublishPayload]'s bytes as a UTF-8 string. The
 * reverse of [opaquePublishPayloadOf] for assertion-side comparison.
 */
internal fun OpaquePublishPayload.asUtf8String(): String {
    val view = handle.asReadBuffer()
    return view.readString(view.remaining(), Charset.UTF8)
}

/**
 * Reproduces the v4 PUBLISH fixed-header first-byte layout used by tests reconstructing
 * PUBLISH packets from raw fields (topic/qos/retain/dup/payload) to build [com.ditchoom.mqtt.controlpacket.MqttFixedHeader].
 */
internal fun makePublishHeaderByteV4(
    dup: Boolean,
    qos: QualityOfService,
    retain: Boolean,
): UByte {
    val dupBit = if (dup) 0x08 else 0x00
    val retainBit = if (retain) 0x01 else 0x00
    val qosBits = qos.integerValue.toInt() shl 1
    return ((3 shl 4) or dupBit or qosBits or retainBit).toUByte()
}
