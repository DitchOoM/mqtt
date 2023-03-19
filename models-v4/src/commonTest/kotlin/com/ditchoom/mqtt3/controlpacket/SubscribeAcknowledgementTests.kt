package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscribeAcknowledgementTests {
    private val packetIdentifier = 2

    @Test
    fun successMaxQos0() {
        val buffer = PlatformBuffer.allocate(5)
        val payload = GRANTED_QOS_0
        val puback = SubscribeAcknowledgement(packetIdentifier, listOf(payload))
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as SubscribeAcknowledgement
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
        assertEquals(pubackResult.payload, listOf(GRANTED_QOS_0))
    }

    @Test
    fun grantedQos1() {
        val payload = GRANTED_QOS_1
        val puback = SubscribeAcknowledgement(packetIdentifier, listOf(payload))
        val buffer = PlatformBuffer.allocate(5)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as SubscribeAcknowledgement
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
        assertEquals(pubackResult.payload, listOf(GRANTED_QOS_1))
    }

    @Test
    fun grantedQos2() {
        val payload = GRANTED_QOS_2
        val puback = SubscribeAcknowledgement(packetIdentifier, listOf(payload))
        val buffer = PlatformBuffer.allocate(5)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as SubscribeAcknowledgement
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
        assertEquals(pubackResult.payload, listOf(GRANTED_QOS_2))
    }

    @Test
    fun failure() {
        val payload = UNSPECIFIED_ERROR
        val puback = SubscribeAcknowledgement(packetIdentifier, listOf(payload))
        val buffer = PlatformBuffer.allocate(5)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV4.from(buffer) as SubscribeAcknowledgement
        assertEquals(pubackResult.packetIdentifier, packetIdentifier)
        assertEquals(pubackResult.payload, listOf(UNSPECIFIED_ERROR))
    }
}
