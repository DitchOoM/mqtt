package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishReleaseTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val buffer = PlatformBuffer.allocate(4)
        val puback = PublishRelease(packetIdentifier)
        assertEquals(4, puback.packetSize())
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as PublishRelease
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
    }
}
