package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishAcknowledgementTest {
    private val packetIdentifier = 2.toUShort()

    @Test
    fun packetIdentifier() {
        val buffer = BufferFactory.Default.allocate(4)
        val puback = PublishAcknowledgment(packetIdentifier)
        assertEquals(4, puback.packetSize())
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.packetIdentifier, packetIdentifier.toInt())
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val buffer = BufferFactory.Default.allocate(4)
        val puback = PublishAcknowledgment(packetIdentifier)
        assertEquals(4, puback.packetSize())
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.packetIdentifier, packetIdentifier.toInt())
    }

    @Test
    fun wireFormatBytes() {
        val puback = PublishAcknowledgment(0x1234.toUShort())
        val buffer = BufferFactory.Default.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0x40.toByte(), buffer.readByte()) // byte1: type=4, flags=0
        assertEquals(0x02.toByte(), buffer.readByte()) // VBI: remainingLength=2
        assertEquals(0x12.toByte(), buffer.readByte()) // packetId MSB
        assertEquals(0x34.toByte(), buffer.readByte()) // packetId LSB
        assertEquals(0, buffer.remaining()) // no trailing bytes
    }

    @Test
    fun valueClassEquality() {
        val a = PublishAcknowledgment(42.toUShort())
        val b = PublishAcknowledgment(42.toUShort())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
