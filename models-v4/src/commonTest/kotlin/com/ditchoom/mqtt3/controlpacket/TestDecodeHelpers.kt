package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.opaqueBytesFrom
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService

/**
 * Test convenience for `ControlPacketV4.from(buffer)` which buffer-v1 collapsed (the
 * companion `.from(buffer)` overloads were deleted). Tests that don't care about the
 * PUBLISH payload type (PUBACK, SUBACK, CONNECT, etc.) route through this helper.
 *
 * PUBLISH application bytes are decoded as [OpaquePublishPayload] (Pattern #2 — consumer-
 * owned `PlatformBuffer`) — spec-compliant, no UTF-8 loss. Tests asserting payload content
 * compare via `OpaquePublishPayload.handle.handleEquals(...)` or by re-decoding the bytes
 * through a specific codec.
 */
internal fun decodeV4(buffer: ReadBuffer): ControlPacketV4<OpaquePublishPayload> =
    ControlPacketV4Codec(OpaquePublishPayloadCodec).decode(buffer, DecodeContext.Empty)

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
    // OpaqueBytesHandle.byteSize() returns the wrapped buffer's full `capacity`, so we
    // must allocate exactly the UTF-8 byte count (not s.length * 4 max-width).
    val bytes = s.encodeToByteArray()
    val buf = BufferFactory.Default.allocate(bytes.size)
    buf.writeBytes(bytes)
    buf.resetForRead()
    return OpaquePublishPayload(opaqueBytesFrom(buf))
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
