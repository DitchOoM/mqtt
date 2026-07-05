package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishReleaseTests {
    private val packetIdentifier = 2.toUShort()

    @Test
    fun packetIdentifier() {
        val buffer = BufferFactory.Default.allocate(4)
        val puback = PublishRelease(packetIdentifier)
        assertEquals(4, packetSizeV4(puback))
        serializeV4(puback, buffer)
        buffer.resetForRead()
        val pubackResult = decodeV4(buffer) as PublishRelease
        assertEquals(pubackResult.packetIdentifier, packetIdentifier.toInt())
    }

    @Test
    fun wireFormatBytes() {
        val pubrel = PublishRelease(0x1234.toUShort())
        val buffer = BufferFactory.Default.allocate(4)
        serializeV4(pubrel, buffer)
        buffer.resetForRead()
        assertEquals(0x62.toByte(), buffer.readByte()) // byte1: type=6, flags=0b0010
        assertEquals(0x02.toByte(), buffer.readByte()) // VBI: remainingLength=2
        assertEquals(0x12.toByte(), buffer.readByte()) // packetId MSB
        assertEquals(0x34.toByte(), buffer.readByte()) // packetId LSB
        assertEquals(0, buffer.remaining())
    }
}
