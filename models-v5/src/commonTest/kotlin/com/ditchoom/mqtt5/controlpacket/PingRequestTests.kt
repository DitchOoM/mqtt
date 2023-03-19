package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class PingRequestTests {
    @Test
    fun serializeDeserialize() {
        val buffer = PlatformBuffer.allocate(2)
        val ping = PingRequest
        ping.serialize(buffer)
        buffer.resetForRead()
        assertEquals(12.shl(4).toByte(), buffer.readByte())
        assertEquals(0, buffer.readByte())
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer)
        assertEquals(result, ping)
    }
}
