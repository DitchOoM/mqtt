package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishCompleteTests {
    private val packetIdentifier = 2.toUShort()

    @Test
    fun packetIdentifier() {
        val puback = PublishComplete(packetIdentifier)
        assertEquals(4, packetSizeV4(puback))
        val buffer = BufferFactory.Default.allocate(4)
        serializeV4(puback, buffer)
        buffer.resetForRead()
        val pubackResult = decodeV4(buffer) as PublishComplete
        assertEquals(pubackResult.packetIdentifier, packetIdentifier.toInt())
    }

    @Test
    fun wireFormatBytes() {
        val pubcomp = PublishComplete(0x1234.toUShort())
        val buffer = BufferFactory.Default.allocate(4)
        serializeV4(pubcomp, buffer)
        buffer.resetForRead()
        assertEquals(0x70.toByte(), buffer.readByte()) // byte1: type=7, flags=0
        assertEquals(0x02.toByte(), buffer.readByte()) // VBI: remainingLength=2
        assertEquals(0x12.toByte(), buffer.readByte()) // packetId MSB
        assertEquals(0x34.toByte(), buffer.readByte()) // packetId LSB
        assertEquals(0, buffer.remaining())
    }
}
