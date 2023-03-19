@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS")

package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedInvalidVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import kotlin.js.JsName
import kotlin.jvm.JvmName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableByteIntegerTests {

    private val VARIABLE_BYTE_INT_MAX = 268435455

    @Test
    @JsName("encodedValueMustUseMinNumberOfBytes")
    @JvmName("encodedValueMustUseMinNumberOfBytes")
    fun `MQTT Conformance The encoded value MUST use the minimum number of bytes necessary to represent the value`() {
        val oneMin = 0
        assertEquals(1, variableByteSize(oneMin))
        val oneMax = 127
        assertEquals(1, variableByteSize(oneMax))
        val twoMin = 128
        assertEquals(2, variableByteSize(twoMin))
        val twoMax = 16_383
        assertEquals(2, variableByteSize(twoMax))
        val threeMin = 16_384
        assertEquals(3, variableByteSize(threeMin))
        val threeMax = 2_097_151
        assertEquals(3, variableByteSize(threeMax))
        val fourMin = 2_097_152
        assertEquals(4, variableByteSize(fourMin))
        val fourMax = 268_435_455
        assertEquals(4, variableByteSize(fourMax))
    }

    @Test
    fun handles0() {
        val expectedValue = 0
        val buffer = PlatformBuffer.allocate(1)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles1() {
        val expectedValue = 1
        val buffer = PlatformBuffer.allocate(1)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles127() {
        val expectedValue = 127
        val buffer = PlatformBuffer.allocate(1)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles128() {
        val expectedValue = 128
        val buffer = PlatformBuffer.allocate(2)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles16383() {
        val expectedValue = 16383
        val buffer = PlatformBuffer.allocate(2)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles16384() {
        val expectedValue = 16384
        val buffer = PlatformBuffer.allocate(3)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handles65535() {
        val expectedValue = 65535
        val buffer = PlatformBuffer.allocate(3)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handlesMaxMinus1() {
        val expectedValue = VARIABLE_BYTE_INT_MAX - 1
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handlesMax() {
        val expectedValue = VARIABLE_BYTE_INT_MAX
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeVariableByteInteger(expectedValue)
        buffer.resetForRead()
        assertEquals(
            expectedValue,
            buffer.readVariableByteInteger(),
            "Failed to read remaining bytes properly"
        )
    }

    @Test
    fun handlesMaxPlus1() {
        val expectedValue = VARIABLE_BYTE_INT_MAX + 1
        val buffer = PlatformBuffer.allocate(4)
        assertFailsWith(
            MalformedInvalidVariableByteInteger::class,
            "Larger than variable byte integer maximum"
        ) {
            buffer.writeVariableByteInteger(expectedValue)
            buffer.resetForRead()
            buffer.readVariableByteInteger()
        }
    }
}
