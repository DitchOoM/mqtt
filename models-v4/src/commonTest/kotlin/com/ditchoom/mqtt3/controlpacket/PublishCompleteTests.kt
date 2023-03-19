package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishCompleteTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val puback = PublishComplete(packetIdentifier)
        assertEquals(4, puback.packetSize())
        val buffer = PlatformBuffer.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishComplete
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
    }
}
