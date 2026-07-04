package com.ditchoom.mqtt

import com.ditchoom.buffer.codec.DecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Guards the [mappingMalformedWire] contract at the production decode boundary (DitchOoM/mqtt#13):
 * malformed wire bytes surface as an [MqttException] / [MalformedPacketException], while genuine
 * decoder bugs propagate unwrapped so they are never masked as "the broker sent garbage".
 */
class DecodeBoundaryTest {
    // Local types whose simpleName matches the name-matched buffer/charset families.
    private class BufferUnderflowException : Exception("truncated frame")

    private class MalformedInputException : Exception("bad utf-8")

    @Test
    fun passesThroughValueWhenNoThrow() {
        assertEquals(42, mappingMalformedWire { 42 })
    }

    @Test
    fun wrapsDecodeExceptionAsMalformedPacket() {
        // DecodeException extends IllegalStateException, so this also proves the DecodeException
        // branch is checked BEFORE plain ISE is treated as a bug.
        assertFailsWith<MalformedPacketException> {
            mappingMalformedWire {
                throw DecodeException(fieldPath = "packetType", bufferPosition = 0, expected = "1..15", actual = "0")
            }
        }
    }

    @Test
    fun wrapsIllegalArgumentAsMalformedPacket() {
        assertFailsWith<MalformedPacketException> {
            mappingMalformedWire { throw IllegalArgumentException("invalid reason code") }
        }
    }

    @Test
    fun wrapsBufferUnderflowFamilyByName() {
        assertFailsWith<MalformedPacketException> {
            mappingMalformedWire { throw BufferUnderflowException() }
        }
    }

    @Test
    fun wrapsCharsetFamilyByName() {
        assertFailsWith<MalformedPacketException> {
            mappingMalformedWire { throw MalformedInputException() }
        }
    }

    @Test
    fun wrapsJsRuntimeMalformedByMessage() {
        assertTrue(isMalformedWireException(RuntimeException("Offset is outside the bounds of the DataView")))
        assertTrue(isMalformedWireException(RuntimeException("The encoded data was not valid for encoding utf-8")))
    }

    @Test
    fun passesExistingMqttExceptionThroughUnchanged() {
        val original = ProtocolError("already typed")
        val thrown =
            assertFailsWith<ProtocolError> {
                mappingMalformedWire { throw original }
            }
        // Not re-wrapped into MalformedPacketException — same instance, same type.
        assertSame(original, thrown)
    }

    @Test
    fun doesNotMaskNullPointerAsMalformed() {
        // A genuine decoder bug MUST propagate unwrapped, not become a MalformedPacketException.
        assertFailsWith<NullPointerException> {
            mappingMalformedWire { throw NullPointerException("decoder bug") }
        }
    }

    @Test
    fun doesNotMaskIllegalStateAsMalformed() {
        assertFailsWith<IllegalStateException> {
            mappingMalformedWire { throw IllegalStateException("unsupported version") }
        }
    }
}
