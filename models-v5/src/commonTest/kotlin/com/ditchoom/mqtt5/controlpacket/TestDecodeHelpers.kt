package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.mqtt.controlpacket.QualityOfService

/**
 * Test convenience for `ControlPacketV5.from(buffer)` typing — PUBLISH payloads route
 * through the Phase A intermediary [NonSpecCompliantIntermediaryStringAsBuffer]. Tests
 * that don't care about the PUBLISH payload type (CONNECT, CONNACK, PUBACK, etc.)
 * should use this helper.
 */
internal fun decodeV5(buffer: ReadBuffer): ControlPacketV5<NonSpecCompliantIntermediaryStringAsBuffer> =
    ControlPacketV5Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).decode(buffer, DecodeContext.Empty)

/**
 * Test convenience replacing the now-gone `ControlPacket.serialize(WriteBuffer)` API.
 * Routes the variant through the v5 sealed-tree codec. PUBLISH payloads are intermediated
 * through [NonSpecCompliantIntermediaryStringAsBuffer] for tests.
 */
@Suppress("UNCHECKED_CAST")
internal fun serializeV5(
    packet: ControlPacketV5<*>,
    buffer: WriteBuffer,
) {
    val encoded =
        ControlPacketV5Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
            packet as ControlPacketV5<NonSpecCompliantIntermediaryStringAsBuffer>,
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
        ControlPacketV5Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
            packet as ControlPacketV5<NonSpecCompliantIntermediaryStringAsBuffer>,
            EncodeContext.Empty,
            BufferFactory.Default,
        )
    return encoded.remaining()
}

/** Allocate a fresh ReadBuffer containing the encoded form of [packet]. */
@Suppress("UNCHECKED_CAST")
internal fun encodeToReadBufferV5(packet: ControlPacketV5<*>): ReadBuffer =
    ControlPacketV5Codec(NonSpecCompliantIntermediaryStringAsBufferCodec).encode(
        packet as ControlPacketV5<NonSpecCompliantIntermediaryStringAsBuffer>,
        EncodeContext.Empty,
        BufferFactory.Default,
    )

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
