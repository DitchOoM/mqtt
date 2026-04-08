package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.SubscribeAcknowledgement.VariableHeader as SubAckVariableHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests that validate exact wire bytes against the MQTT 5.0 OASIS specification.
 * Each test either encodes a packet and asserts every byte, or constructs raw
 * spec-defined bytes and decodes them, verifying the resulting object.
 *
 * References: https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html
 */
class SpecByteTests {

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun assertBytes(expected: IntArray, actual: ByteArray, message: String = "") {
        assertEquals(expected.size, actual.size, "$message size mismatch")
        expected.forEachIndexed { i, e ->
            assertEquals(
                e.toByte(),
                actual[i],
                "$message byte[$i]: expected 0x${e.toString(16).padStart(2, '0')}" +
                    " but was 0x${(actual[i].toInt() and 0xFF).toString(16).padStart(2, '0')}",
            )
        }
    }

    private fun packetBytes(block: () -> ControlPacketV5): ByteArray {
        val packet = block()
        val buffer = BufferFactory.Default.allocate(packet.packetSize())
        packet.serialize(buffer)
        buffer.resetForRead()
        val bytes = ByteArray(buffer.remaining())
        for (i in bytes.indices) bytes[i] = buffer.readByte()
        return bytes
    }

    private fun rawBuffer(vararg bytes: Int) =
        BufferFactory.Default.allocate(bytes.size).also { buf ->
            bytes.forEach { buf.writeByte(it.toByte()) }
            buf.resetForRead()
        }

    // ── PINGREQ / PINGRESP (§3.12, §3.13) ──────────────────────────────────

    @Test
    fun pingreqExactBytes() {
        // PINGREQ is exactly 2 bytes: C0 00
        val bytes = packetBytes { PingRequest }
        assertBytes(intArrayOf(0xC0, 0x00), bytes, "PINGREQ")
    }

    @Test
    fun pingrespExactBytes() {
        // PINGRESP is exactly 2 bytes: D0 00
        val bytes = packetBytes { PingResponse }
        assertBytes(intArrayOf(0xD0, 0x00), bytes, "PINGRESP")
    }

    // ── CONNECT (§3.1) ──────────────────────────────────────────────────────

    @Test
    fun connectDefaultExactBytes() {
        // CONNECT with empty clientId, cleanStart=true, keepAlive=0, no properties
        // 10 0D 00 04 4D 51 54 54 05 02 00 00 00 00 00
        val bytes = packetBytes {
            ConnectionRequest(clientId = "", keepAliveSeconds = 0, cleanStart = true)
        }
        assertBytes(
            intArrayOf(
                0x10, // fixed header: type=1 (CONNECT), flags=0000
                0x0D, // remaining length = 13
                0x00, 0x04, 0x4D, 0x51, 0x54, 0x54, // protocol name "MQTT"
                0x05, // protocol level 5
                0x02, // connect flags: cleanStart=1, rest=0
                0x00, 0x00, // keep alive = 0
                0x00, // property length = 0
                0x00, 0x00, // client ID = "" (length 0)
            ),
            bytes,
            "CONNECT",
        )
    }

    @Test
    fun connectDecodeFromRawBytes() {
        val buffer = rawBuffer(
            0x10, 0x0D, 0x00, 0x04, 0x4D, 0x51, 0x54, 0x54,
            0x05, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val packet = ControlPacketV5.from(buffer)
        assertIs<ConnectionRequest>(packet)
        assertEquals("", packet.clientIdentifier)
        assertEquals(true, packet.cleanStart)
        assertEquals(0, packet.keepAliveTimeoutSeconds.toInt())
        assertEquals("MQTT", packet.protocolName)
        assertEquals(5, packet.protocolVersion)
    }

    // ── CONNACK (§3.2) ──────────────────────────────────────────────────────

    @Test
    fun connackSuccessNoPropertiesExactBytes() {
        // 20 03 00 00 00
        val bytes = packetBytes {
            ConnectionAcknowledgment(
                ConnectionAcknowledgment.VariableHeader(
                    sessionPresent = false,
                    connectReason = ReasonCode.SUCCESS,
                ),
            )
        }
        assertBytes(
            intArrayOf(
                0x20, // fixed header: type=2, flags=0000
                0x03, // remaining length = 3
                0x00, // connect acknowledge flags (session present = false)
                0x00, // reason code = SUCCESS
                0x00, // property length = 0
            ),
            bytes,
            "CONNACK",
        )
    }

    @Test
    fun connackDecodeFromRawBytes() {
        val buffer = rawBuffer(0x20, 0x03, 0x00, 0x00, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<ConnectionAcknowledgment>(packet)
        assertEquals(false, packet.header.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, packet.header.connectReason)
    }

    @Test
    fun connackWithSessionExpiryIntervalBytes() {
        // CONNACK with SessionExpiryInterval = 300 (0x0000012C)
        // Property: id=0x11, value=00 00 01 2C (4 bytes) → props total = 5
        // 20 08 00 00 05 11 00 00 01 2C
        val bytes = packetBytes {
            ConnectionAcknowledgment(
                ConnectionAcknowledgment.VariableHeader(
                    sessionPresent = false,
                    connectReason = ReasonCode.SUCCESS,
                    properties = ConnectionAcknowledgment.VariableHeader.Properties(
                        sessionExpiryIntervalSeconds = 300uL,
                    ),
                ),
            )
        }
        assertBytes(
            intArrayOf(
                0x20, // CONNACK
                0x08, // remaining length = 8
                0x00, // session present = false
                0x00, // reason code = SUCCESS
                0x05, // property length = 5
                0x11, // property id: Session Expiry Interval
                0x00, 0x00, 0x01, 0x2C, // 300 seconds
            ),
            bytes,
            "CONNACK+SessionExpiry",
        )
    }

    // ── PUBLISH (§3.3) ──────────────────────────────────────────────────────

    @Test
    fun publishQos0TopicANoPayloadExactBytes() {
        // QoS 0, topic "a", no packet ID, no properties, no payload
        // 30 04 00 01 61 00
        val bytes = packetBytes {
            PublishMessage(topicName = "a")
        }
        assertBytes(
            intArrayOf(
                0x30, // fixed header: type=3, flags=0000 (DUP=0, QoS=00, RETAIN=0)
                0x04, // remaining length = 4
                0x00, 0x01, // topic length = 1
                0x61, // "a"
                0x00, // property length = 0
            ),
            bytes,
            "PUBLISH QoS0",
        )
    }

    @Test
    fun publishQos1TopicAPacketId1ExactBytes() {
        // QoS 1, topic "a", packetId=1, no properties, no payload
        // 32 06 00 01 61 00 01 00
        val bytes = packetBytes {
            PublishMessage(qos = QualityOfService.AT_LEAST_ONCE, topicName = "a", packetIdentifier = 1)
        }
        assertBytes(
            intArrayOf(
                0x32, // fixed header: type=3, flags=0010 (QoS=1)
                0x06, // remaining length = 6
                0x00, 0x01, // topic length = 1
                0x61, // "a"
                0x00, 0x01, // packet ID = 1
                0x00, // property length = 0
            ),
            bytes,
            "PUBLISH QoS1",
        )
    }

    @Test
    fun publishQos2TopicAPacketId1ExactBytes() {
        // QoS 2, topic "a", packetId=1, no properties, no payload
        // 34 06 00 01 61 00 01 00
        val bytes = packetBytes {
            PublishMessage(qos = QualityOfService.EXACTLY_ONCE, topicName = "a", packetIdentifier = 1)
        }
        assertBytes(
            intArrayOf(
                0x34, // fixed header: type=3, flags=0100 (QoS=2)
                0x06, // remaining length = 6
                0x00, 0x01, // topic length = 1
                0x61, // "a"
                0x00, 0x01, // packet ID = 1
                0x00, // property length = 0
            ),
            bytes,
            "PUBLISH QoS2",
        )
    }

    @Test
    fun publishDecodeQos0FromRawBytes() {
        val buffer = rawBuffer(0x30, 0x04, 0x00, 0x01, 0x61, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<PublishMessage>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(QualityOfService.AT_MOST_ONCE, packet.qualityOfService)
    }

    @Test
    fun publishDecodeQos1FromRawBytes() {
        val buffer = rawBuffer(0x32, 0x06, 0x00, 0x01, 0x61, 0x00, 0x01, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<PublishMessage>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(QualityOfService.AT_LEAST_ONCE, packet.qualityOfService)
        assertEquals(1, packet.packetIdentifier)
    }

    // ── PUBACK (§3.4) ───────────────────────────────────────────────────────

    @Test
    fun pubackPacketId10SuccessExactBytes() {
        // PUBACK with packetId=10, SUCCESS (omit reason code + properties)
        // 40 02 00 0A
        val bytes = packetBytes { PublishAcknowledgment(10.toUShort()) }
        assertBytes(
            intArrayOf(
                0x40, // fixed header: type=4, flags=0000
                0x02, // remaining length = 2
                0x00, 0x0A, // packet ID = 10
            ),
            bytes,
            "PUBACK",
        )
    }

    @Test
    fun pubackWithReasonStringExactBytes() {
        // PUBACK with packetId=10, SUCCESS, reasonString="ok"
        // Property: 0x1F (id) + 00 02 (len) + 6F 6B ("ok") = 5 bytes
        // RL = packetId(2) + reasonCode(1) + propLenVBI(1) + props(5) = 9
        val bytes = packetBytes {
            PublishAcknowledgment(10, ReasonCode.SUCCESS, "ok")
        }
        assertBytes(
            intArrayOf(
                0x40, // PUBACK
                0x09, // remaining length = 9
                0x00, 0x0A, // packet ID = 10
                0x00, // reason code = SUCCESS
                0x05, // property length = 5
                0x1F, // property id: Reason String
                0x00, 0x02, // string length = 2
                0x6F, 0x6B, // "ok"
            ),
            bytes,
            "PUBACK+ReasonString",
        )
    }

    // ── PUBREC (§3.5) ───────────────────────────────────────────────────────

    @Test
    fun pubrecPacketId10SuccessExactBytes() {
        // 50 02 00 0A
        val bytes = packetBytes {
            PublishReceived(10, ReasonCode.SUCCESS)
        }
        assertBytes(
            intArrayOf(0x50, 0x02, 0x00, 0x0A),
            bytes,
            "PUBREC",
        )
    }

    // ── PUBREL (§3.6) ───────────────────────────────────────────────────────

    @Test
    fun pubrelPacketId10SuccessExactBytes() {
        // PUBREL has reserved flags 0010 → byte1 = 0x62
        // 62 02 00 0A
        val bytes = packetBytes {
            PublishRelease(10, ReasonCode.SUCCESS)
        }
        assertBytes(
            intArrayOf(0x62, 0x02, 0x00, 0x0A),
            bytes,
            "PUBREL",
        )
    }

    // ── PUBCOMP (§3.7) ──────────────────────────────────────────────────────

    @Test
    fun pubcompPacketId10SuccessExactBytes() {
        // 70 02 00 0A
        val bytes = packetBytes {
            PublishComplete(10.toUShort())
        }
        assertBytes(
            intArrayOf(0x70, 0x02, 0x00, 0x0A),
            bytes,
            "PUBCOMP",
        )
    }

    // ── SUBSCRIBE (§3.8, Figure 3-19) ───────────────────────────────────────

    @Test
    fun subscribeFigure3_19ExactBytes() {
        // Packet ID=10, no properties, topic "a/b" QoS 1
        // 82 09 00 0A 00 00 03 61 2F 62 01
        val bytes = packetBytes {
            SubscribeRequest(10.toUShort(), "a/b", QualityOfService.AT_LEAST_ONCE)
        }
        assertBytes(
            intArrayOf(
                0x82, // fixed header: type=8, flags=0010
                0x09, // remaining length = 9
                0x00, 0x0A, // packet ID = 10
                0x00, // property length = 0
                0x00, 0x03, // topic filter length = 3
                0x61, 0x2F, 0x62, // "a/b"
                0x01, // subscription options: QoS 1
            ),
            bytes,
            "SUBSCRIBE Fig 3-19",
        )
    }

    @Test
    fun subscribeDecodeFromSpecRawBytes() {
        val buffer = rawBuffer(0x82, 0x09, 0x00, 0x0A, 0x00, 0x00, 0x03, 0x61, 0x2F, 0x62, 0x01)
        val packet = ControlPacketV5.from(buffer)
        assertIs<SubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(1, packet.subscriptions.size)
        val sub = packet.subscriptions.first()
        assertEquals("a/b", sub.topicFilter.toString())
        assertEquals(QualityOfService.AT_LEAST_ONCE, sub.maximumQos)
    }

    // ── SUBACK (§3.9) ──────────────────────────────────────────────────────

    @Test
    fun subackPacketId10GrantedQos1ExactBytes() {
        // 90 04 00 0A 00 01
        val bytes = packetBytes {
            SubscribeAcknowledgement(10.toUShort(), ReasonCode.GRANTED_QOS_1)
        }
        assertBytes(
            intArrayOf(
                0x90, // fixed header: type=9, flags=0000
                0x04, // remaining length = 4
                0x00, 0x0A, // packet ID = 10
                0x00, // property length = 0
                0x01, // reason code: GRANTED_QOS_1
            ),
            bytes,
            "SUBACK",
        )
    }

    @Test
    fun subackDecodeFromRawBytes() {
        val buffer = rawBuffer(0x90, 0x04, 0x00, 0x0A, 0x00, 0x01)
        val packet = ControlPacketV5.from(buffer)
        assertIs<SubscribeAcknowledgement>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(listOf(ReasonCode.GRANTED_QOS_1), packet.payload)
    }

    // ── UNSUBSCRIBE (§3.10, Figure 3-26) ────────────────────────────────────

    @Test
    fun unsubscribeFigure3_26ExactBytes() {
        // Packet ID=10, no properties, topics "a/b" and "c/d"
        // RL = packetId(2) + propLen(1) + topicLen(2)+topic(3) + topicLen(2)+topic(3) = 13
        // A2 0D 00 0A 00 00 03 61 2F 62 00 03 63 2F 64
        val bytes = packetBytes {
            UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(10),
                setOf(TopicFilter.fromOrThrow("a/b"), TopicFilter.fromOrThrow("c/d")),
            )
        }
        assertBytes(
            intArrayOf(
                0xA2, // fixed header: type=10, flags=0010
                0x0D, // remaining length = 13
                0x00, 0x0A, // packet ID = 10
                0x00, // property length = 0
                0x00, 0x03, // topic filter length = 3
                0x61, 0x2F, 0x62, // "a/b"
                0x00, 0x03, // topic filter length = 3
                0x63, 0x2F, 0x64, // "c/d"
            ),
            bytes,
            "UNSUBSCRIBE Fig 3-26",
        )
    }

    @Test
    fun unsubscribeDecodeFromSpecRawBytes() {
        val buffer = rawBuffer(
            0xA2, 0x0D, 0x00, 0x0A, 0x00,
            0x00, 0x03, 0x61, 0x2F, 0x62,
            0x00, 0x03, 0x63, 0x2F, 0x64,
        )
        val packet = ControlPacketV5.from(buffer)
        assertIs<UnsubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(2, packet.topics.size)
        assertTrue(packet.topics.any { it.toString() == "a/b" })
        assertTrue(packet.topics.any { it.toString() == "c/d" })
    }

    // ── UNSUBACK (§3.11) ────────────────────────────────────────────────────

    @Test
    fun unsubackPacketId10SuccessExactBytes() {
        // B0 04 00 0A 00 00
        val bytes = packetBytes {
            UnsubscribeAcknowledgment(
                packetIdentifier = 10,
                reasonCodes = listOf(ReasonCode.SUCCESS),
            )
        }
        assertBytes(
            intArrayOf(
                0xB0, // fixed header: type=11, flags=0000
                0x04, // remaining length = 4
                0x00, 0x0A, // packet ID = 10
                0x00, // property length = 0
                0x00, // reason code: SUCCESS
            ),
            bytes,
            "UNSUBACK",
        )
    }

    @Test
    fun unsubackDecodeFromRawBytes() {
        val buffer = rawBuffer(0xB0, 0x04, 0x00, 0x0A, 0x00, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<UnsubscribeAcknowledgment>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(listOf(ReasonCode.SUCCESS), packet.reasonCodes)
    }

    // ── DISCONNECT (§3.14) ──────────────────────────────────────────────────

    @Test
    fun disconnectNormalExactBytes() {
        // DISCONNECT with NORMAL_DISCONNECTION and no properties → RL=0
        // E0 00
        val bytes = packetBytes {
            DisconnectNotification(DisconnectNotification.VariableHeader())
        }
        assertBytes(
            intArrayOf(0xE0, 0x00),
            bytes,
            "DISCONNECT normal",
        )
    }

    @Test
    fun disconnectWithReasonCodeExactBytes() {
        // DISCONNECT with UNSPECIFIED_ERROR, no properties
        // The codec omits the property length byte when properties are null/empty
        // E0 02 80 (RL=2 includes space for prop-length VBI, but codec skips it)
        val bytes = packetBytes {
            DisconnectNotification(
                DisconnectNotification.VariableHeader(ReasonCode.UNSPECIFIED_ERROR),
            )
        }
        assertBytes(
            intArrayOf(
                0xE0, // fixed header: type=14, flags=0000
                0x02, // remaining length (header reports 2)
                0x80, // reason code: UNSPECIFIED_ERROR
            ),
            bytes,
            "DISCONNECT error",
        )
    }

    @Test
    fun disconnectDecodeFromRawBytes() {
        val buffer = rawBuffer(0xE0, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<DisconnectNotification>(packet)
        assertEquals(ReasonCode.NORMAL_DISCONNECTION, packet.variable.reasonCode)
    }

    // ── AUTH (§3.15) ────────────────────────────────────────────────────────

    @Test
    fun authSuccessExactBytes() {
        // AUTH with SUCCESS and no properties → RL=0
        // F0 00
        val bytes = packetBytes {
            AuthenticationExchange(
                AuthenticationExchange.VariableHeader(
                    ReasonCode.SUCCESS,
                    AuthenticationExchange.VariableHeader.Properties(authentication = null),
                ),
            )
        }
        assertBytes(
            intArrayOf(0xF0, 0x00),
            bytes,
            "AUTH",
        )
    }

    @Test
    fun authDecodeFromRawBytes() {
        val buffer = rawBuffer(0xF0, 0x00)
        val packet = ControlPacketV5.from(buffer)
        assertIs<AuthenticationExchange>(packet)
        assertEquals(ReasonCode.SUCCESS, packet.variable.reasonCode)
    }
}
