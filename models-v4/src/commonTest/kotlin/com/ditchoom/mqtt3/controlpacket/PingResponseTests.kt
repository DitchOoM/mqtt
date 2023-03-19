package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class PingResponseTests {
    @Test
    fun serializeDeserialize() {
        val ping = PingResponse
        val buffer = PlatformBuffer.allocate(2)
        ping.serialize(buffer)
        buffer.resetForRead()
        assertEquals(13.shl(4).toByte(), buffer.readByte())
        assertEquals(0, buffer.readByte())

        val buffer2 = PlatformBuffer.allocate(2)
        ping.serialize(buffer2)
        buffer2.resetForRead()
        val result = ControlPacketV4.from(buffer2)
        assertEquals(result, ping)
    }
}
