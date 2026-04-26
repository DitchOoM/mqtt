package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec-edge-case round-trip tests for the four list-shaped packets migrated to `V5Packet`:
 * SUBSCRIBE, SUBACK, UNSUBSCRIBE, UNSUBACK.
 *
 * Each is validated for:
 *  (a) full-wire round trip via `ControlPacket.serialize()` → `ControlPacketV5.from()`
 *  (b) reserved fixed-header low-nibble bits (SUBSCRIBE/UNSUBSCRIBE pin to 0010)
 *  (c) empty payload rejection (Protocol Error per §3.8.3 / §3.10.3 / §3.11.3)
 *  (d) Subscription Options bit packing — reserved bits 6-7 must be 0, retain handling != 3,
 *      max QoS != 3 (Protocol Error per §3.8.3.1)
 *  (e) Per-packet reason-code valid-set enforcement on construction and decode
 */
class V5PacketListShapedTests {
    // ── SUBSCRIBE ───────────────────────────────────────────────────────────

    @Test
    fun subscribeSingleTopicQos1RoundTrip() {
        val pkt = SubscribeRequest(packetIdentifier = 10.toUShort(), topic = "a/b", qos = QualityOfService.AT_LEAST_ONCE)
        val buf = pkt.serialize()
        assertEquals(0x82.toByte(), buf.readByte()) // type=8, reserved low nibble 0010
        buf.position(0)
        val decoded = ControlPacketV5.from(buf)
        assertIs<SubscribeRequest>(decoded)
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(1, decoded.subscriptions.size)
        val sub = decoded.subscriptions.first()
        assertEquals("a/b", sub.topicFilter.toString())
        assertEquals(QualityOfService.AT_LEAST_ONCE, sub.maximumQos)
    }

    @Test
    fun subscribeAllOptionsBitsRoundTrip() {
        val pkt = SubscribeRequest(
            packetIdentifier = 99.toUShort(),
            topic = "test",
            qos = QualityOfService.EXACTLY_ONCE,
            noLocal = true,
            retainAsPublished = true,
            retainHandling = DO_NOT_SEND_RETAINED_MESSAGES,
            reasonString = "diag",
            userProperty = listOf("k" to "v"),
        )
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf) as SubscribeRequest
        val sub = decoded.subscriptions.first()
        assertEquals(QualityOfService.EXACTLY_ONCE, sub.maximumQos)
        assertTrue(sub.noLocal)
        assertTrue(sub.retainAsPublished)
        assertEquals(DO_NOT_SEND_RETAINED_MESSAGES, sub.retainHandling)
        assertEquals("diag", decoded.properties.reasonStringValue())
        assertEquals(listOf("k" to "v"), decoded.properties.userProperties())
    }

    @Test
    fun subscribeMultipleTopicsRoundTrip() {
        val pkt = SubscribeRequest(
            packetIdentifier = 5,
            topics = listOf(
                TopicFilter.fromOrThrow("topic1"),
                TopicFilter.fromOrThrow("topic2"),
                TopicFilter.fromOrThrow("topic3"),
            ),
            qos = listOf(
                QualityOfService.AT_MOST_ONCE,
                QualityOfService.AT_LEAST_ONCE,
                QualityOfService.EXACTLY_ONCE,
            ),
        )
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf) as SubscribeRequest
        assertEquals(3, decoded.subscriptions.size)
        val sortedSubs = decoded.subscriptions.sortedBy { it.topicFilter.toString() }
        assertEquals(QualityOfService.AT_MOST_ONCE, sortedSubs[0].maximumQos)
        assertEquals(QualityOfService.AT_LEAST_ONCE, sortedSubs[1].maximumQos)
        assertEquals(QualityOfService.EXACTLY_ONCE, sortedSubs[2].maximumQos)
    }

    @Test
    fun subscribeReservedBitsRejectedAtDecode() {
        // Manually craft a SUBSCRIBE with reserved bit 6 set in subscription options.
        val buf = BufferFactory.Default.allocate(20)
        buf.writeUByte(0x82u) // type + reserved flags
        buf.writeUByte(0x07u) // RL=7: 2 packetId + 1 propLen + (2 strLen + 1 'a' + 1 opts) = 7
        buf.writeUShort(1u)
        buf.writeUByte(0x00u) // properties length 0
        buf.writeUShort(1u)
        buf.writeByte('a'.code.toByte())
        buf.writeUByte(0x40u) // reserved bit 6 set
        buf.resetForRead()
        assertFailsWith<IllegalArgumentException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun subscribeRetainHandlingValue3RejectedAtConstruction() {
        // Constructing directly with malformed entry should fail spec validation.
        assertFailsWith<IllegalArgumentException> {
            V5Packet.Subscribe(
                packetId = 1.toUShort(),
                properties = emptyList(),
                subscriptionEntries = listOf(SubscriptionV5Entry("a", 0x30u)), // rh=3
            )
        }
    }

    @Test
    fun subscribeMaxQos3RejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            V5Packet.Subscribe(
                packetId = 1.toUShort(),
                properties = emptyList(),
                subscriptionEntries = listOf(SubscriptionV5Entry("a", 0x03u)), // qos=3
            )
        }
    }

    @Test
    fun subscribeEmptyPayloadRejected() {
        assertFailsWith<IllegalArgumentException> {
            V5Packet.Subscribe(packetId = 1.toUShort(), properties = emptyList(), subscriptionEntries = emptyList())
        }
    }

    @Test
    fun subscribeWithInvalidReservedFlagsRejected() {
        // Spec §3.8.1: reserved low-nibble MUST be 0010. Try 0000.
        val buf = BufferFactory.Default.allocate(10)
        buf.writeUByte(0x80u) // bad flags (should be 0x82)
        buf.writeUByte(0x07u)
        buf.writeUShort(1u)
        buf.writeUByte(0x00u)
        buf.writeUShort(1u)
        buf.writeByte('a'.code.toByte())
        buf.writeUByte(0x00u)
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { ControlPacketV5.from(buf) }
    }

    // ── SUBACK ──────────────────────────────────────────────────────────────

    @Test
    fun subAckSinglePayloadRoundTrip() {
        val pkt = SubscribeAcknowledgement(packetIdentifier = 10.toUShort(), payload = ReasonCode.GRANTED_QOS_1)
        val buf = pkt.serialize()
        assertEquals(0x90.toByte(), buf.readByte())
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as SubscribeAcknowledgement
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(listOf(ReasonCode.GRANTED_QOS_1), decoded.payload)
    }

    @Test
    fun subAckMultipleReasonCodesRoundTrip() {
        val codes = listOf(
            ReasonCode.GRANTED_QOS_0,
            ReasonCode.GRANTED_QOS_2,
            ReasonCode.UNSPECIFIED_ERROR,
            ReasonCode.NOT_AUTHORIZED,
        )
        val pkt = SubscribeAcknowledgement(packetIdentifier = 1.toUShort(), reasonCodes = codes)
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf) as SubscribeAcknowledgement
        assertEquals(codes, decoded.payload)
    }

    @Test
    fun subAckEmptyPayloadRejected() {
        assertFailsWith<IllegalArgumentException> {
            V5Packet.SubAck(packetId = 1.toUShort(), properties = emptyList(), reasonCodeEntries = emptyList())
        }
    }

    @Test
    fun subAckInvalidReasonCodeRejected() {
        // 0x10 (NO_MATCHING_SUBSCRIBERS) is in PUBACK's set but not SUBACK's.
        assertFailsWith<ProtocolError> {
            SubscribeAcknowledgement(
                packetIdentifier = 1.toUShort(),
                reasonCodes = listOf(ReasonCode.NO_MATCHING_SUBSCRIBERS),
            )
        }
    }

    @Test
    fun subAckInvalidReasonCodeOnWireRejectedAtDecode() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeUByte(0x90u)
        buf.writeUByte(0x04u)
        buf.writeUShort(1u)
        buf.writeUByte(0x00u) // props len
        buf.writeUByte(0x10u) // not in SUBACK valid set
        buf.resetForRead()
        // Constructor's init { } validation throws ProtocolError before MalformedPacketException
        // would fire in the lazy `payload` accessor.
        assertFailsWith<ProtocolError> { ControlPacketV5.from(buf) }
    }

    // ── UNSUBSCRIBE ─────────────────────────────────────────────────────────

    @Test
    fun unsubscribeSingleTopicRoundTrip() {
        val pkt = UnsubscribeRequest("a/b")
        val buf = pkt.serialize()
        assertEquals(0xA2.toByte(), buf.readByte())
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as UnsubscribeRequest
        assertEquals(setOf(TopicFilter.fromOrThrow("a/b")), decoded.topics)
    }

    @Test
    fun unsubscribeMultipleTopicsRoundTrip() {
        val pkt = UnsubscribeRequest(
            packetIdentifier = 10.toUShort(),
            topics = setOf(TopicFilter.fromOrThrow("a/b"), TopicFilter.fromOrThrow("c/d")),
            userProperty = listOf("trace" to "abc"),
        )
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf) as UnsubscribeRequest
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(2, decoded.topics.size)
        assertEquals(listOf("trace" to "abc"), decoded.properties.userProperties())
    }

    @Test
    fun unsubscribeEmptyPayloadRejected() {
        assertFailsWith<ProtocolError> {
            V5Packet.Unsubscribe(packetId = 1.toUShort(), properties = emptyList(), topicEntries = emptyList())
        }
    }

    @Test
    fun unsubscribeWithInvalidReservedFlagsRejected() {
        // Spec §3.10.1: reserved low-nibble MUST be 0010. Try 0000.
        val buf = BufferFactory.Default.allocate(10)
        buf.writeUByte(0xA0u) // bad flags (should be 0xA2)
        buf.writeUByte(0x06u)
        buf.writeUShort(1u)
        buf.writeUByte(0x00u)
        buf.writeUShort(1u)
        buf.writeByte('a'.code.toByte())
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { ControlPacketV5.from(buf) }
    }

    // ── UNSUBACK ────────────────────────────────────────────────────────────

    @Test
    fun unsubAckSingleSuccessRoundTrip() {
        val pkt = UnsubscribeAcknowledgment(packetIdentifier = 10)
        val buf = pkt.serialize()
        assertEquals(0xB0.toByte(), buf.readByte())
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as UnsubscribeAcknowledgment
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(listOf(ReasonCode.SUCCESS), decoded.reasonCodes)
    }

    @Test
    fun unsubAckMultipleReasonCodesRoundTrip() {
        val codes = listOf(
            ReasonCode.SUCCESS,
            ReasonCode.NO_SUBSCRIPTIONS_EXISTED,
            ReasonCode.NOT_AUTHORIZED,
        )
        val pkt = UnsubscribeAcknowledgment(
            packetIdentifier = 7,
            reasonString = "partial",
            userProperty = listOf("k" to "v"),
            reasonCodes = codes,
        )
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf) as UnsubscribeAcknowledgment
        assertEquals(codes, decoded.reasonCodes)
        assertEquals("partial", decoded.properties.reasonStringValue())
    }

    @Test
    fun unsubAckEmptyPayloadRejected() {
        assertFailsWith<ProtocolError> {
            V5Packet.UnsubAck(packetId = 1.toUShort(), properties = emptyList(), reasonCodeEntries = emptyList())
        }
    }

    @Test
    fun unsubAckInvalidReasonCodeRejected() {
        // GRANTED_QOS_2 is valid in SUBACK only; not in UNSUBACK's table.
        assertFailsWith<ProtocolError> {
            UnsubscribeAcknowledgment(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.GRANTED_QOS_2))
        }
    }

    @Test
    fun unsubAckInvalidReasonCodeOnWireRejectedAtDecode() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeUByte(0xB0u)
        buf.writeUByte(0x04u)
        buf.writeUShort(1u)
        buf.writeUByte(0x00u) // props len
        buf.writeUByte(0x05u) // not in UNSUBACK valid set
        buf.resetForRead()
        assertFailsWith<ProtocolError> { ControlPacketV5.from(buf) }
    }
}
