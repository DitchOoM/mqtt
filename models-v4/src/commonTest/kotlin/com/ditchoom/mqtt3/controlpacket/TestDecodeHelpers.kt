package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.mqtt.controlpacket.QualityOfService

/**
 * Test convenience for `ControlPacketV4.from(buffer)` which buffer-v1 collapsed (the
 * companion `.from(buffer)` overloads were deleted). Tests that don't care about the
 * PUBLISH payload type (PUBACK, SUBACK, CONNECT, etc.) route through this helper.
 *
 * PUBLISH application bytes are intermediated via [NonSpecCompliantIntermediaryStringAsBuffer]
 * — see that type's kdoc for the Phase A → Phase B deferral. Tests asserting payload
 * content should compare the typed payload directly, or migrate to a consumer codec.
 */
internal fun decodeV4(buffer: ReadBuffer): ControlPacketV4<NonSpecCompliantIntermediaryStringAsBuffer> =
    ControlPacketV4Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).decode(buffer, DecodeContext.Empty)

/**
 * Test convenience replacing the now-gone `ControlPacket.serialize(WriteBuffer)` API.
 * Routes the variant through the v4 sealed-tree codec. PUBLISH payloads are intermediated
 * through [NonSpecCompliantIntermediaryStringAsBuffer] for tests.
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
        ControlPacketV4Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
            packet as ControlPacketV4<NonSpecCompliantIntermediaryStringAsBuffer>,
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
        ControlPacketV4Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
            packet as ControlPacketV4<NonSpecCompliantIntermediaryStringAsBuffer>,
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
    ControlPacketV4Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
        packet as ControlPacketV4<NonSpecCompliantIntermediaryStringAsBuffer>,
        EncodeContext.Empty,
        BufferFactory.Default,
    )

/**
 * Reproduces the v4 PUBLISH fixed-header first-byte layout used by the production
 * `SqlDatabasePersistence` private helper. Tests reconstruct PUBLISH packets from raw
 * fields (topic/qos/retain/dup/payload) and need this byte to build `MqttFixedHeader`.
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
