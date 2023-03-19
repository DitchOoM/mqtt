package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class DisconnectTests {
    @Test
    fun serializeDeserialize() {
        val actual = DisconnectNotification
        val buffer = PlatformBuffer.allocate(2)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV4.from(buffer) as DisconnectNotification
        assertEquals(expected, actual)
    }
}
