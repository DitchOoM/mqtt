package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class DisconnectTests {
    @Test
    fun serializeDeserialize() {
        val actual = DisconnectNotification
        val buffer = BufferFactory.Default.allocate(2)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV4.from(buffer) as DisconnectNotification
        assertEquals(expected, actual)
    }
}
