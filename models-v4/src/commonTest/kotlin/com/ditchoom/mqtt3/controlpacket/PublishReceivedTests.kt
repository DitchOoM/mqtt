package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishReceivedTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val puback = PublishReceived(packetIdentifier)
        assertEquals(4, puback.packetSize())
        val buffer = BufferFactory.Default.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishReceived
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val puback = PublishReceived(packetIdentifier)
        assertEquals(4, puback.packetSize())
        val buffer = BufferFactory.Default.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishReceived
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
    }

    @Test
    fun wireFormatBytes() {
        val pubrec = PublishReceived(0x1234)
        val buffer = BufferFactory.Default.allocate(4)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0x50.toByte(), buffer.readByte()) // byte1: type=5, flags=0
        assertEquals(0x02.toByte(), buffer.readByte()) // VBI: remainingLength=2
        assertEquals(0x12.toByte(), buffer.readByte()) // packetId MSB
        assertEquals(0x34.toByte(), buffer.readByte()) // packetId LSB
        assertEquals(0, buffer.remaining())
    }
}
