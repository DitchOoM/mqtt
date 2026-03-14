package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PingResponseTests {
    @Test
    fun serializeDeserialize() {
        val ping = PingResponse
        val buffer = BufferFactory.Default.allocate(2)
        ping.serialize(buffer)
        buffer.resetForRead()
        assertEquals(13.shl(4).toByte(), buffer.readByte())
        assertEquals(0, buffer.readByte())

        val buffer2 = BufferFactory.Default.allocate(2)
        ping.serialize(buffer2)
        buffer2.resetForRead()
        val result = ControlPacketV4.from(buffer2)
        assertEquals(result, ping)
    }

    @Test
    fun wireFormatBytes() {
        val buffer = BufferFactory.Default.allocate(2)
        PingResponse.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0xD0.toByte(), buffer.readByte()) // byte1: type=13
        assertEquals(0x00.toByte(), buffer.readByte()) // VBI: remainingLength=0
        assertEquals(0, buffer.remaining())
    }
}
