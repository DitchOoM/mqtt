package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagTests {
    private val packetIdentifier = 1

    private val controlPacketSpectMatchError =
        "doesn't match the spec from " +
            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477323"

    // Control packet types flagBits.matchesEmptyBits() matching spec
    @Test
    fun controlPacketFlagsMatchSpecForCONNECT() = assertEquals(ConnectionRequest().flags, 0b0, controlPacketSpectMatchError)

    @Test
    fun byte1CONNECT() = assertEquals(ConnectionRequest().flags, 0b0, controlPacketSpectMatchError)

    @Test
    fun controlPacketFlagsMatchSpecForCONNACK() = assertEquals(ConnectionAcknowledgment().flags, 0b0, controlPacketSpectMatchError)

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_AtMostOnce_Retain_false() {
        val detailed = V5Packet.Publish.ofRaw(topic = TopicName.fromOrThrow("t"))
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(detailed.flags, 0b0, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_trueQos_AtMostOnceRetain_false() {
        val expected = 0b1000.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                dup = true,
                retain = false,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_AtMostOnce_retain_true() {
        val expected = 0b1.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                dup = false,
                retain = true,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_true_Qos_AtMostOnce_retain_true() {
        val expected = 0b1001.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                dup = true,
                retain = true,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_AtLeastOnce_retain_false() {
        val expected = 0b10.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                dup = false,
                retain = false,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(
            detailed.controlPacketValue,
            0x03,
            "Invalid Byte 1 in the fixed header: Control Packet Value",
        )
        val buffer = BufferFactory.Default.allocate(8)
        detailed.serialize(buffer)
        buffer.resetForRead()
        val byteAsUInt = buffer.readByte().toUInt()

        assertEquals(
            byteAsUInt.shr(4),
            0x03.toUInt(),
            "Invalid Byte 1 in the fixed header: Control Packet Value serialize shift right 4 times",
        )
        val expectedFlagMatch =
            byteAsUInt
                .shl(4)
                .toByte()
                .toInt()
                .shr(4)
                .toByte()
        assertEquals(
            expectedFlagMatch,
            0b0010,
            "Invalid Byte 1 in the fixed header: Flags dont match",
        )
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_true_Qos_AtLeastOnce_retain_false() {
        val expected = 0b1010.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                dup = true,
                retain = false,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_AtLeastOnce_retain_true() {
        val expected = 0b11.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                dup = false,
                retain = true,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_true_Qos_AtLeastOnce_retain_true() {
        val expected = 0b1011.toByte()
        val simple =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                dup = true,
                retain = true,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(simple.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, simple.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_ExactlyOnce_retain_false() {
        val expected = 0b100.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                dup = false,
                retain = false,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_true_Qos_ExactlyOnce_retain_false() {
        val expected = 0b1100.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                dup = true,
                retain = false,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_false_Qos_ExactlyOnce_retain_true() {
        val expected = 0b101.toByte()
        val detailed =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                dup = false,
                retain = true,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(detailed.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, detailed.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBLISH_dup_true_Qos_ExactlyOnce_retain_true() {
        val expected = 0b1101.toByte()
        val simple =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                dup = true,
                retain = true,
                packetIdentifier = packetIdentifier,
            )
        assertEquals(simple.controlPacketValue, 0x03, "invalid byte controlPacketValue")
        assertEquals(expected, simple.flags, controlPacketSpectMatchError)
    }

    @Test
    fun controlPacketFlagsMatchSpecForPUBACK() =
        assertEquals(
            PublishAcknowledgment(packetIdentifier).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForPUBREC() =
        assertEquals(
            PublishReceived(packetIdentifier).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForPUBREL() =
        assertEquals(
            PublishRelease(packetIdentifier).flags,
            0b10,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForPUBCOMP() =
        assertEquals(
            PublishComplete(packetIdentifier).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForSUBSCRIBE() =
        assertEquals(
            0b10,
            SubscribeRequest(packetIdentifier.toUShort(), "yolo", AT_LEAST_ONCE).flags,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForSUBACK() =
        assertEquals(
            SubscribeAcknowledgement(packetIdentifier.toUShort(), GRANTED_QOS_0).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForUNSUBSCRIBE() =
        assertEquals(
            0b10,
            UnsubscribeRequest("yolo").flags,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForUNSUBACK() =
        assertEquals(
            UnsubscribeAcknowledgment(packetIdentifier, reasonCodes = listOf(SUCCESS)).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForPINGREQ() = assertEquals(PingRequest.flags, 0b0, controlPacketSpectMatchError)

    @Test
    fun controlPacketFlagsMatchSpecForPINGRESP() = assertEquals(PingResponse.flags, 0b0, controlPacketSpectMatchError)

    @Test
    fun controlPacketFlagsMatchSpecForDISCONNECT() =
        assertEquals(
            DisconnectNotification(reasonCode = NORMAL_DISCONNECTION).flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketFlagsMatchSpecForAUTH() =
        assertEquals(
            AuthenticationExchange().flags,
            0b0,
            controlPacketSpectMatchError,
        )

    @Test
    fun emptyFlagBitsTest() {
        assertEquals(0b0, 0x00)
    }

    @Test
    fun bit1TrueFlagBitsTest() {
        assertEquals(0b10, 0x02)
    }

    @Test
    fun uByteToBoolean0b0() = assertEquals(0b00000000.toUByte().get(0), false)

    @Test
    fun uByteToBoolean0b00000001First() = assertEquals(0b00000001.toUByte().get(0), true)

    @Test
    fun uByteToBoolean0b00000001Last() {
        val ubyte = 0b10000000.toUByte()
        val ubyteAsInt = ubyte.toInt()
        val intShifted = ubyteAsInt.shl(7 - 7)
        val shiftedAsUbyte = intShifted.toUByte()
        val shiftedAsInt = shiftedAsUbyte.toInt()
        val shiftedRight = shiftedAsInt.shr(7)

        assertEquals(shiftedRight == 1, true)
    }
}
