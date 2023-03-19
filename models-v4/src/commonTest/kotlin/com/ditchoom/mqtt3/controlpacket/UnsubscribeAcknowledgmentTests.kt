package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsubscribeAcknowledgmentTests {
    private val packetIdentifier = 2

    @Test
    fun serializeDeserializeDefault() {
        val buffer = PlatformBuffer.allocate(4)
        val actual = UnsubscribeAcknowledgment(packetIdentifier)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV4.from(buffer)
        assertEquals(expected, actual)
    }
}
