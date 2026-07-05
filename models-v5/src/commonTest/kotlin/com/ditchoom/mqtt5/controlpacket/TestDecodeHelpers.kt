package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
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
 * Test convenience replacing the retired `ControlPacketV5.from(buffer)` companion —
 * PUBLISH payloads decoded into [OpaquePublishPayload] (Pattern #2, byte-exact). Tests
 * that don't care about the PUBLISH payload type (CONNECT, CONNACK, PUBACK, etc.) should
 * use this helper.
 *
 * Preserves `from()`'s `DecodeException → MalformedPacketException` remap so the ~dozen
 * `assertFailsWith<MalformedPacketException>` sites keep passing. `ProtocolError` /
 * `IllegalArgumentException` are NOT caught (matching the retired `from()`), so those
 * assertions still see the raw type. Its narrow catch differs from production's
 * [com.ditchoom.mqtt.mappingMalformedWire] (which also remaps `IllegalArgumentException` / the
 * buffer-underflow / charset families) — [decodeProductionV5] is the faithful production mirror.
 */
internal fun decodeV5(buffer: ReadBuffer): ControlPacketV5<OpaquePublishPayload> =
    try {
        ControlPacketV5Codec(OpaquePublishPayloadCodec).decode(buffer, DecodeContext.Empty)
    } catch (e: DecodeException) {
        throw MalformedPacketException(e.message ?: "malformed control packet")
    }

/**
 * Production-mirroring decode: routes through the generated `decodeAggregating`
 * companion **exactly** as `MqttCodec.decode` does — the PUBLISH branch goes through
 * the partial-then-`complete` aggregation path (not the instance-codec `publishCodec.decode`
 * that [decodeV5] uses). The two paths differ (the aggregation path skips the
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
internal fun decodeProductionV5(buffer: ReadBuffer): ControlPacketV5<OpaquePublishPayload> =
    mappingMalformedWire {
        ControlPacketV5Codec.decodeAggregating(
            buffer = buffer,
            context = DecodeContext.Empty,
            onPublish = { it.complete(OpaquePublishPayloadCodec) },
        )
    }

/**
 * Test convenience replacing the now-gone `ControlPacket.serialize(WriteBuffer)` API.
 * Routes the variant through the v5 sealed-tree codec with [OpaquePublishPayloadCodec].
 */
@Suppress("UNCHECKED_CAST")
internal fun serializeV5(
    packet: ControlPacketV5<*>,
    buffer: WriteBuffer,
) {
    val encoded =
        ControlPacketV5Codec(OpaquePublishPayloadCodec).encode(
            packet as ControlPacketV5<OpaquePublishPayload>,
            EncodeContext.Empty,
            BufferFactory.Default,
        )
    buffer.write(encoded)
}

/**
 * Test convenience replacing `ControlPacket.packetSize()`. Encodes once to a scratch
 * buffer and returns its remaining byte count.
 */
@Suppress("UNCHECKED_CAST")
internal fun packetSizeV5(packet: ControlPacketV5<*>): Int {
    val encoded =
        ControlPacketV5Codec(OpaquePublishPayloadCodec).encode(
            packet as ControlPacketV5<OpaquePublishPayload>,
            EncodeContext.Empty,
            BufferFactory.Default,
        )
    return encoded.remaining()
}

/** Allocate a fresh ReadBuffer containing the encoded form of [packet]. */
@Suppress("UNCHECKED_CAST")
internal fun encodeToReadBufferV5(packet: ControlPacketV5<*>): ReadBuffer =
    ControlPacketV5Codec(OpaquePublishPayloadCodec).encode(
        packet as ControlPacketV5<OpaquePublishPayload>,
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
    val len = view.remaining()
    return view.readString(len, com.ditchoom.buffer.Charset.UTF8)
}

/**
 * Reproduces the v5 PUBLISH fixed-header first-byte layout. Tests reconstruct PUBLISH
 * packets from raw fields (topic/qos/retain/dup/payload) and need this byte to build
 * `MqttFixedHeader`.
 */
internal fun makePublishHeaderByteV5(
    dup: Boolean,
    qos: QualityOfService,
    retain: Boolean,
): UByte {
    val dupBit = if (dup) 0x08 else 0x00
    val retainBit = if (retain) 0x01 else 0x00
    val qosBits = qos.integerValue.toInt() shl 1
    return ((3 shl 4) or dupBit or qosBits or retainBit).toUByte()
}
