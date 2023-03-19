package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsubscribeRequestTests {
    private val packetIdentifier = 2

    @Test
    fun basicTest() {
        val buffer = PlatformBuffer.allocate(17)
        val unsub = UnsubscribeRequest(packetIdentifier, setOf("yolo", "yolo1"))
        unsub.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV4.from(buffer) as UnsubscribeRequest
        val topics = result.topics.sortedBy { it.toString() }
        assertEquals(topics.first().toString(), "yolo")
        assertEquals(topics[1].toString(), "yolo1")
    }
}
