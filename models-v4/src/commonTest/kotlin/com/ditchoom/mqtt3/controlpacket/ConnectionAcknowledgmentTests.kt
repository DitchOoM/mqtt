package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionAcknowledgmentTests {
    @Test
    fun serializeDeserializeDefault() {
        val buffer = BufferFactory.Default.allocate(4)
        val actual = ConnectionAcknowledgment()
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV4.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun bit0SessionPresentFalseFlags() {
        val buffer = BufferFactory.Default.allocate(4)
        val model = ConnectionAcknowledgment()
        model.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV4.from(buffer) as ConnectionAcknowledgment
        assertFalse(result.header.sessionPresent)
    }

    @Test
    fun bit0SessionPresentFlags() {
        val buffer = BufferFactory.Default.allocate(4)
        val model = ConnectionAcknowledgment(ConnectionAcknowledgment.VariableHeader(true))
        model.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV4.from(buffer) as ConnectionAcknowledgment
        assertTrue(result.header.sessionPresent)
    }
}
