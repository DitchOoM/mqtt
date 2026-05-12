package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsubscribeAcknowledgmentTests {
    private val packetIdentifier = 2.toUShort()

    @Test
    fun serializeDeserializeDefault() {
        val buffer = BufferFactory.Default.allocate(4)
        val actual = UnsubscribeAcknowledgment(packetIdentifier)
        serializeV4(actual, buffer)
        buffer.resetForRead()
        val expected = decodeV4(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun wireFormatBytes() {
        val unsuback = UnsubscribeAcknowledgment(0x1234.toUShort())
        val buffer = BufferFactory.Default.allocate(4)
        serializeV4(unsuback, buffer)
        buffer.resetForRead()
        assertEquals(0xB0.toByte(), buffer.readByte()) // byte1: type=11, flags=0
        assertEquals(0x02.toByte(), buffer.readByte()) // VBI: remainingLength=2
        assertEquals(0x12.toByte(), buffer.readByte()) // packetId MSB
        assertEquals(0x34.toByte(), buffer.readByte()) // packetId LSB
        assertEquals(0, buffer.remaining())
    }
}
