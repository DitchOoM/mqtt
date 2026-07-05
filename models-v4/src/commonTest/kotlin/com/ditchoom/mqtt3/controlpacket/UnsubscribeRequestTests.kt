package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsubscribeRequestTests {
    private val packetIdentifier = 2

    @Test
    fun basicTest() {
        val buffer = BufferFactory.Default.allocate(17)
        val unsub = UnsubscribeRequest(packetIdentifier, setOf("yolo", "yolo1"))
        serializeV4(unsub, buffer)
        buffer.resetForRead()
        val result = decodeV4(buffer) as UnsubscribeRequest
        val topics = result.topics.sortedBy { it.toString() }
        assertEquals(topics.first().toString(), "yolo")
        assertEquals(topics[1].toString(), "yolo1")
    }
}
