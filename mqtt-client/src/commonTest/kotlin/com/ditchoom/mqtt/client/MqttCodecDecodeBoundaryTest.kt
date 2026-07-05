package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Factory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the production decode boundary (`MqttCodec.decode`) wraps malformed wire bytes into a
 * [MalformedPacketException] (DitchOoM/mqtt#13) while still decoding valid frames unchanged. The
 * classification itself is unit-tested in models-base `DecodeBoundaryTest`; this pins the wrap to
 * the actual `MqttCodec.decode` call site over `decodeAggregating`.
 */
class MqttCodecDecodeBoundaryTest {
    private val codec = MqttCodec(ControlPacketV5Factory)

    private fun bufferOf(vararg bytes: Int): ReadBuffer {
        val array = ByteArray(bytes.size) { bytes[it].toByte() }
        val buffer = BufferFactory.Default.allocate(array.size)
        buffer.writeBytes(array)
        buffer.resetForRead()
        return buffer
    }

    @Test
    fun decodesValidPacket() {
        // PINGRESP: fixed header 0xD0, remaining length 0.
        val packet = codec.decode(bufferOf(0xD0, 0x00), DecodeContext.Empty)
        assertTrue(packet is IPingResponse, "expected PINGRESP, got ${packet::class.simpleName}")
    }

    @Test
    fun wrapsTruncatedPublishAsMalformedPacket() {
        // PUBLISH (0x30), remaining length 5, topic length 3, but only one topic byte present:
        // decoding the topic underflows the frame, which must surface as MalformedPacketException.
        assertFailsWith<MalformedPacketException> {
            codec.decode(bufferOf(0x30, 0x05, 0x00, 0x03, 0x61), DecodeContext.Empty)
        }
    }

    @Test
    fun wrapsReservedPacketTypeAsMalformedPacket() {
        // Control packet type 0 is reserved/forbidden — decodeAggregating rejects it.
        assertFailsWith<MalformedPacketException> {
            codec.decode(bufferOf(0x00, 0x00), DecodeContext.Empty)
        }
    }
}
