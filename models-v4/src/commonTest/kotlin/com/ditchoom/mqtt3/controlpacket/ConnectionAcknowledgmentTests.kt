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
        serializeV4(actual, buffer)
        buffer.resetForRead()
        val expected = decodeV4(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun bit0SessionPresentFalseFlags() {
        val buffer = BufferFactory.Default.allocate(4)
        val model = ConnectionAcknowledgment()
        serializeV4(model, buffer)
        buffer.resetForRead()
        val result = decodeV4(buffer) as ConnectionAcknowledgment
        assertFalse(result.sessionPresent)
    }

    @Test
    fun bit0SessionPresentFlags() {
        val buffer = BufferFactory.Default.allocate(4)
        val model = ConnectionAcknowledgment(ConnectionAcknowledgment.VariableHeader(true))
        serializeV4(model, buffer)
        buffer.resetForRead()
        val result = decodeV4(buffer) as ConnectionAcknowledgment
        assertTrue(result.sessionPresent)
    }
}
