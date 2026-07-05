package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class PingRequestTests {
    @Test
    fun serializeDeserialize() {
        val ping = PingRequest()
        val buffer = BufferFactory.Default.allocate(4)
        serializeV4(ping, buffer)
        buffer.resetForRead()
        assertEquals(12.shl(4).toByte(), buffer.readByte())
        assertEquals(0, buffer.readByte())

        val buffer2 = BufferFactory.Default.allocate(4)
        serializeV4(ping, buffer2)
        buffer2.resetForRead()
        val result = decodeV4(buffer2)
        assertEquals(result, ping)
    }

    @Test
    fun wireFormatBytes() {
        val buffer = BufferFactory.Default.allocate(2)
        serializeV4(PingRequest(), buffer)
        buffer.resetForRead()
        assertEquals(0xC0.toByte(), buffer.readByte()) // byte1: type=12
        assertEquals(0x00.toByte(), buffer.readByte()) // VBI: remainingLength=0
        assertEquals(0, buffer.remaining())
    }
}
