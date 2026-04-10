package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that validate exact wire bytes against the MQTT 5.0 OASIS specification.
 * Each test either encodes a packet and asserts every byte inline, or constructs raw
 * spec-defined bytes in a buffer and decodes them, verifying the resulting object.
 *
 * References: https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html
 */
class SpecByteTests {

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun packetBuffer(block: () -> ControlPacketV5): ReadBuffer {
        val packet = block()
        val buffer = BufferFactory.Default.allocate(packet.packetSize())
        packet.serialize(buffer)
        buffer.resetForRead()
        return buffer
    }

    // ── PINGREQ / PINGRESP (§3.12, §3.13) ──────────────────────────────────

    @Test
    fun pingreqExactBytes() {
        val buf = packetBuffer { PingRequest }
        assertEquals(2, buf.remaining())
        assertEquals(0xC0u, buf.readUnsignedByte()) // type=12, flags=0000
        assertEquals(0x00u, buf.readUnsignedByte()) // remaining length = 0
    }

    @Test
    fun pingrespExactBytes() {
        val buf = packetBuffer { PingResponse }
        assertEquals(2, buf.remaining())
        assertEquals(0xD0u, buf.readUnsignedByte()) // type=13, flags=0000
        assertEquals(0x00u, buf.readUnsignedByte()) // remaining length = 0
    }

    // ── CONNECT (§3.1) ──────────────────────────────────────────────────────

    @Test
    fun connectDefaultExactBytes() {
        // Empty clientId, cleanStart=true, keepAlive=0, no properties
        val buf = packetBuffer {
            ConnectionRequest(clientId = "", keepAliveSeconds = 0, cleanStart = true)
        }
        assertEquals(15, buf.remaining())
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1
        assertEquals(0x0Du, buf.readUnsignedByte()) // RL=13
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte()) // "MQTT" len
        assertEquals(0x4Du, buf.readUnsignedByte()); assertEquals(0x51u, buf.readUnsignedByte()) // MQ
        assertEquals(0x54u, buf.readUnsignedByte()); assertEquals(0x54u, buf.readUnsignedByte()) // TT
        assertEquals(0x05u, buf.readUnsignedByte()) // protocol level 5
        assertEquals(0x02u, buf.readUnsignedByte()) // flags: cleanStart=1
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // keepAlive=0
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // clientId=""
    }

    @Test
    fun connectDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(15)
        buf.writeUByte(0x10u); buf.writeUByte(0x0Du) // type=1, RL=13
        buf.writeUByte(0x00u); buf.writeUByte(0x04u) // "MQTT" len
        buf.writeUByte(0x4Du); buf.writeUByte(0x51u); buf.writeUByte(0x54u); buf.writeUByte(0x54u)
        buf.writeUByte(0x05u) // level=5
        buf.writeUByte(0x02u) // cleanStart
        buf.writeUByte(0x00u); buf.writeUByte(0x00u) // keepAlive=0
        buf.writeUByte(0x00u) // props len=0
        buf.writeUByte(0x00u); buf.writeUByte(0x00u) // clientId=""
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<ConnectionRequest>(packet)
        assertEquals("", packet.clientIdentifier)
        assertTrue(packet.cleanStart)
        assertEquals(0, packet.keepAliveTimeoutSeconds.toInt())
        assertEquals(5, packet.protocolVersion)
    }

    @Test
    fun connectWithWillUsernamePasswordExactBytes() {
        val willPayload = BufferFactory.Default.allocate(1)
        willPayload.writeUByte(0x70u) // 'p'
        willPayload.resetForRead()
        val buf = packetBuffer {
            ConnectionRequest(
                clientId = "c",
                keepAliveSeconds = 60,
                cleanStart = true,
                userName = "u",
                password = "x",
                will = WillConfig.Enabled(TopicName.fromOrThrow("w"), willPayload, AT_LEAST_ONCE, retain = false),
            )
        }
        // flags = username(1) password(1) willRetain(0) willQos(01) willFlag(1) cleanStart(1) reserved(0)
        //       = 1100_1110 = 0xCE
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1
        buf.readUnsignedByte() // RL (verified via total remaining)
        // Protocol name "MQTT"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte())
        assertEquals(0x4Du, buf.readUnsignedByte()); assertEquals(0x51u, buf.readUnsignedByte())
        assertEquals(0x54u, buf.readUnsignedByte()); assertEquals(0x54u, buf.readUnsignedByte())
        assertEquals(0x05u, buf.readUnsignedByte()) // level=5
        assertEquals(0xCEu, buf.readUnsignedByte()) // connect flags
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x3Cu, buf.readUnsignedByte()) // keepAlive=60
        assertEquals(0x00u, buf.readUnsignedByte()) // connect properties len=0
        // Payload: clientId "c"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x63u, buf.readUnsignedByte())
        // Will properties length = 0
        assertEquals(0x00u, buf.readUnsignedByte())
        // Will topic "w"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x77u, buf.readUnsignedByte())
        // Will payload: length-prefixed 1 byte
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x70u, buf.readUnsignedByte()) // 'p'
        // Username "u"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x75u, buf.readUnsignedByte())
        // Password "x"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x78u, buf.readUnsignedByte())
        assertEquals(0, buf.remaining()) // fully consumed
    }

    // ── CONNACK (§3.2) ──────────────────────────────────────────────────────

    @Test
    fun connackSuccessNoPropertiesExactBytes() {
        val buf = packetBuffer {
            ConnectionAcknowledgment(
                ConnectionAcknowledgment.VariableHeader(sessionPresent = false, connectReason = ReasonCode.SUCCESS),
            )
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x00u, buf.readUnsignedByte()) // session present = false
        assertEquals(0x00u, buf.readUnsignedByte()) // reason code = SUCCESS
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    @Test
    fun connackDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(5)
        buf.writeUByte(0x20u); buf.writeUByte(0x03u) // type=2, RL=3
        buf.writeUByte(0x00u) // session present = false
        buf.writeUByte(0x00u) // reason code = SUCCESS
        buf.writeUByte(0x00u) // props len=0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<ConnectionAcknowledgment>(packet)
        assertFalse(packet.header.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, packet.header.connectReason)
    }

    @Test
    fun connackWithSessionExpiryIntervalBytes() {
        // SessionExpiryInterval = 300 (0x0000012C)
        // Property: id=0x11 + value=4 bytes → props=5 bytes
        val buf = packetBuffer {
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
        assertEquals(10, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2
        assertEquals(0x08u, buf.readUnsignedByte()) // RL=8
        assertEquals(0x00u, buf.readUnsignedByte()) // session present
        assertEquals(0x00u, buf.readUnsignedByte()) // reason code = SUCCESS
        assertEquals(0x05u, buf.readUnsignedByte()) // property length = 5
        assertEquals(0x11u, buf.readUnsignedByte()) // property id: Session Expiry Interval
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte())
        assertEquals(0x01u, buf.readUnsignedByte()); assertEquals(0x2Cu, buf.readUnsignedByte()) // 300
    }

    @Test
    fun connackSessionPresentExactBytes() {
        val buf = packetBuffer {
            ConnectionAcknowledgment(
                ConnectionAcknowledgment.VariableHeader(sessionPresent = true, connectReason = ReasonCode.SUCCESS),
            )
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x01u, buf.readUnsignedByte()) // session present = true
        assertEquals(0x00u, buf.readUnsignedByte()) // SUCCESS
        assertEquals(0x00u, buf.readUnsignedByte()) // props len=0
    }

    @Test
    fun connackNotAuthorizedExactBytes() {
        val buf = packetBuffer {
            ConnectionAcknowledgment(
                ConnectionAcknowledgment.VariableHeader(
                    sessionPresent = false,
                    connectReason = ReasonCode.NOT_AUTHORIZED,
                ),
            )
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x00u, buf.readUnsignedByte()) // session present = false
        assertEquals(0x87u, buf.readUnsignedByte()) // NOT_AUTHORIZED = 0x87
        assertEquals(0x00u, buf.readUnsignedByte()) // props len=0
    }

    // ── PUBLISH (§3.3) ──────────────────────────────────────────────────────

    @Test
    fun publishQos0TopicANoPayloadExactBytes() {
        val buf = packetBuffer { PublishMessage(topicName = "a") }
        assertEquals(6, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, flags=0000
        assertEquals(0x04u, buf.readUnsignedByte()) // RL=4
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    @Test
    fun publishQos1TopicAPacketId1ExactBytes() {
        val buf = packetBuffer {
            PublishMessage(qos = AT_LEAST_ONCE, topicName = "a", packetIdentifier = 1)
        }
        assertEquals(8, buf.remaining())
        assertEquals(0x32u, buf.readUnsignedByte()) // type=3, flags=0010 (QoS 1)
        assertEquals(0x06u, buf.readUnsignedByte()) // RL=6
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    @Test
    fun publishQos2TopicAPacketId1ExactBytes() {
        val buf = packetBuffer {
            PublishMessage(qos = EXACTLY_ONCE, topicName = "a", packetIdentifier = 1)
        }
        assertEquals(8, buf.remaining())
        assertEquals(0x34u, buf.readUnsignedByte()) // type=3, flags=0100 (QoS 2)
        assertEquals(0x06u, buf.readUnsignedByte()) // RL=6
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    @Test
    fun publishDecodeQos0FromRawBytes() {
        val buf = BufferFactory.Default.allocate(6)
        buf.writeUByte(0x30u); buf.writeUByte(0x04u) // type=3 QoS0, RL=4
        buf.writeUByte(0x00u); buf.writeUByte(0x01u); buf.writeUByte(0x61u) // topic "a"
        buf.writeUByte(0x00u) // props len=0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<PublishMessage<*>>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(AT_MOST_ONCE, packet.qualityOfService)
    }

    @Test
    fun publishDecodeQos1FromRawBytes() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeUByte(0x32u); buf.writeUByte(0x06u) // type=3 QoS1, RL=6
        buf.writeUByte(0x00u); buf.writeUByte(0x01u); buf.writeUByte(0x61u) // topic "a"
        buf.writeUByte(0x00u); buf.writeUByte(0x01u) // packet ID=1
        buf.writeUByte(0x00u) // props len=0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<PublishMessage<*>>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(AT_LEAST_ONCE, packet.qualityOfService)
        assertEquals(1, packet.packetIdentifier)
    }

    @Test
    fun publishDupRetainFlagsExactBytes() {
        // DUP=1, QoS=1, RETAIN=1 → flags=1011
        val buf = packetBuffer {
            PublishMessage(dup = true, qos = AT_LEAST_ONCE, retain = true, topicName = "a", packetIdentifier = 1)
        }
        assertEquals(8, buf.remaining())
        assertEquals(0x3Bu, buf.readUnsignedByte()) // type=3, flags=1011
        assertEquals(0x06u, buf.readUnsignedByte()) // RL=6
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()) // props len=0
    }

    @Test
    fun publishWithContentTypePropertyExactBytes() {
        // QoS 0, topic "a", contentType="json"
        // Property: id=0x03, value="json" (2+4=6 bytes) → props=7 bytes
        val buf = packetBuffer {
            PublishMessage(topicName = "a", contentType = "json")
        }
        assertEquals(13, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        assertEquals(0x0Bu, buf.readUnsignedByte()) // RL=11
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x07u, buf.readUnsignedByte()) // property length = 7
        assertEquals(0x03u, buf.readUnsignedByte()) // property id: Content Type
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte()) // "json" len=4
        assertEquals(0x6Au, buf.readUnsignedByte()) // 'j'
        assertEquals(0x73u, buf.readUnsignedByte()) // 's'
        assertEquals(0x6Fu, buf.readUnsignedByte()) // 'o'
        assertEquals(0x6Eu, buf.readUnsignedByte()) // 'n'
    }

    @Test
    fun publishMaxPacketIdExactBytes() {
        val buf = packetBuffer {
            PublishMessage(qos = AT_LEAST_ONCE, topicName = "a", packetIdentifier = 0xFFFF)
        }
        assertEquals(8, buf.remaining())
        assertEquals(0x32u, buf.readUnsignedByte()) // type=3, QoS 1
        assertEquals(0x06u, buf.readUnsignedByte()) // RL=6
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0xFFu, buf.readUnsignedByte()); assertEquals(0xFFu, buf.readUnsignedByte()) // max packet ID
        assertEquals(0x00u, buf.readUnsignedByte()) // props len=0
    }

    // ── PUBACK (§3.4) ───────────────────────────────────────────────────────

    @Test
    fun pubackPacketId10SuccessExactBytes() {
        // SUCCESS → omit reason code + properties
        val buf = packetBuffer { PublishAcknowledgment(10.toUShort()) }
        assertEquals(4, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte()) // type=4
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
    }

    @Test
    fun pubackWithReasonStringExactBytes() {
        // packetId=10, SUCCESS, reasonString="ok"
        val buf = packetBuffer {
            PublishAcknowledgment(10, ReasonCode.SUCCESS, "ok")
        }
        assertEquals(11, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte()) // type=4
        assertEquals(0x09u, buf.readUnsignedByte()) // RL=9
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
        assertEquals(0x00u, buf.readUnsignedByte()) // reason code = SUCCESS
        assertEquals(0x05u, buf.readUnsignedByte()) // property length = 5
        assertEquals(0x1Fu, buf.readUnsignedByte()) // property id: Reason String
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x02u, buf.readUnsignedByte()) // "ok" len=2
        assertEquals(0x6Fu, buf.readUnsignedByte()); assertEquals(0x6Bu, buf.readUnsignedByte()) // "ok"
    }

    @Test
    fun pubackNonSuccessReasonCodeExactBytes() {
        // packetId=1, NO_MATCHING_SUBSCRIBERS=0x10, no properties
        val buf = packetBuffer {
            PublishAcknowledgment(AckVariableHeader(1, ReasonCode.NO_MATCHING_SUBSCRIBERS))
        }
        assertEquals(6, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte()) // type=4
        assertEquals(0x04u, buf.readUnsignedByte()) // RL=4
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        assertEquals(0x10u, buf.readUnsignedByte()) // NO_MATCHING_SUBSCRIBERS = 0x10
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    // ── PUBREC (§3.5) ───────────────────────────────────────────────────────

    @Test
    fun pubrecPacketId10SuccessExactBytes() {
        val buf = packetBuffer { PublishReceived(10, ReasonCode.SUCCESS) }
        assertEquals(4, buf.remaining())
        assertEquals(0x50u, buf.readUnsignedByte()) // type=5
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte())
    }

    // ── PUBREL (§3.6) ───────────────────────────────────────────────────────

    @Test
    fun pubrelPacketId10SuccessExactBytes() {
        val buf = packetBuffer { PublishRelease(10, ReasonCode.SUCCESS) }
        assertEquals(4, buf.remaining())
        assertEquals(0x62u, buf.readUnsignedByte()) // type=6, flags=0010
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte())
    }

    // ── PUBCOMP (§3.7) ─────────────────────────────────────────────────────

    @Test
    fun pubcompPacketId10SuccessExactBytes() {
        val buf = packetBuffer { PublishComplete(10.toUShort()) }
        assertEquals(4, buf.remaining())
        assertEquals(0x70u, buf.readUnsignedByte()) // type=7
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte())
    }

    // ── SUBSCRIBE (§3.8) ───────────────────────────────────────────────────

    @Test
    fun subscribeFigure3_19ExactBytes() {
        // Packet ID=10, no properties, topic "a/b" QoS 1
        val buf = packetBuffer {
            SubscribeRequest(10.toUShort(), "a/b", AT_LEAST_ONCE)
        }
        assertEquals(11, buf.remaining())
        assertEquals(0x82u, buf.readUnsignedByte()) // type=8, flags=0010
        assertEquals(0x09u, buf.readUnsignedByte()) // RL=9
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x03u, buf.readUnsignedByte()) // "a/b" len=3
        assertEquals(0x61u, buf.readUnsignedByte()); assertEquals(0x2Fu, buf.readUnsignedByte())
        assertEquals(0x62u, buf.readUnsignedByte())
    }

    @Test
    fun subscribeDecodeFromSpecRawBytes() {
        val buf = BufferFactory.Default.allocate(11)
        buf.writeUByte(0x82u); buf.writeUByte(0x09u) // type=8, RL=9
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u) // props len=0
        buf.writeUByte(0x00u); buf.writeUByte(0x03u) // topic "a/b" len=3
        buf.writeUByte(0x61u); buf.writeUByte(0x2Fu); buf.writeUByte(0x62u)
        buf.writeUByte(0x01u) // subscription options: QoS 1
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<SubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(1, packet.subscriptions.size)
        assertEquals("a/b", packet.subscriptions.first().topicFilter.toString())
        assertEquals(AT_LEAST_ONCE, packet.subscriptions.first().maximumQos)
    }

    // ── SUBACK (§3.9) ──────────────────────────────────────────────────────

    @Test
    fun subackPacketId10GrantedQos1ExactBytes() {
        val buf = packetBuffer {
            SubscribeAcknowledgement(10.toUShort(), ReasonCode.GRANTED_QOS_1)
        }
        assertEquals(6, buf.remaining())
        assertEquals(0x90u, buf.readUnsignedByte()) // type=9
        assertEquals(0x04u, buf.readUnsignedByte()) // RL=4
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        assertEquals(0x01u, buf.readUnsignedByte()) // GRANTED_QOS_1
    }

    @Test
    fun subackDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(6)
        buf.writeUByte(0x90u); buf.writeUByte(0x04u) // type=9, RL=4
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u) // props len=0
        buf.writeUByte(0x01u) // GRANTED_QOS_1
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<SubscribeAcknowledgement>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(listOf(ReasonCode.GRANTED_QOS_1), packet.payload)
    }

    @Test
    fun subackMultipleReasonCodesExactBytes() {
        val buf = packetBuffer {
            SubscribeAcknowledgement(
                SubscribeAcknowledgement.VariableHeader(1),
                listOf(ReasonCode.GRANTED_QOS_0, ReasonCode.GRANTED_QOS_2, ReasonCode.UNSPECIFIED_ERROR),
            )
        }
        assertEquals(8, buf.remaining())
        assertEquals(0x90u, buf.readUnsignedByte()) // type=9
        assertEquals(0x06u, buf.readUnsignedByte()) // RL=6
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        assertEquals(0x00u, buf.readUnsignedByte()) // props len=0
        assertEquals(0x00u, buf.readUnsignedByte()) // GRANTED_QOS_0
        assertEquals(0x02u, buf.readUnsignedByte()) // GRANTED_QOS_2
        assertEquals(0x80u, buf.readUnsignedByte()) // UNSPECIFIED_ERROR = 0x80
    }

    // ── UNSUBSCRIBE (§3.10) ────────────────────────────────────────────────

    @Test
    fun unsubscribeFigure3_26ExactBytes() {
        // Packet ID=10, topics "a/b" and "c/d"
        val buf = packetBuffer {
            UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(10),
                setOf(TopicFilter.fromOrThrow("a/b"), TopicFilter.fromOrThrow("c/d")),
            )
        }
        assertEquals(15, buf.remaining())
        assertEquals(0xA2u, buf.readUnsignedByte()) // type=10, flags=0010
        assertEquals(0x0Du, buf.readUnsignedByte()) // RL=13
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        // "a/b"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x03u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte()); assertEquals(0x2Fu, buf.readUnsignedByte())
        assertEquals(0x62u, buf.readUnsignedByte())
        // "c/d"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x03u, buf.readUnsignedByte())
        assertEquals(0x63u, buf.readUnsignedByte()); assertEquals(0x2Fu, buf.readUnsignedByte())
        assertEquals(0x64u, buf.readUnsignedByte())
    }

    @Test
    fun unsubscribeDecodeFromSpecRawBytes() {
        val buf = BufferFactory.Default.allocate(15)
        buf.writeUByte(0xA2u); buf.writeUByte(0x0Du) // type=10, RL=13
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u) // props len=0
        buf.writeUByte(0x00u); buf.writeUByte(0x03u) // "a/b"
        buf.writeUByte(0x61u); buf.writeUByte(0x2Fu); buf.writeUByte(0x62u)
        buf.writeUByte(0x00u); buf.writeUByte(0x03u) // "c/d"
        buf.writeUByte(0x63u); buf.writeUByte(0x2Fu); buf.writeUByte(0x64u)
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<UnsubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(2, packet.topics.size)
        assertTrue(packet.topics.any { it.toString() == "a/b" })
        assertTrue(packet.topics.any { it.toString() == "c/d" })
    }

    // ── UNSUBACK (§3.11) ───────────────────────────────────────────────────

    @Test
    fun unsubackPacketId10SuccessExactBytes() {
        val buf = packetBuffer {
            UnsubscribeAcknowledgment(packetIdentifier = 10, reasonCodes = listOf(ReasonCode.SUCCESS))
        }
        assertEquals(6, buf.remaining())
        assertEquals(0xB0u, buf.readUnsignedByte()) // type=11
        assertEquals(0x04u, buf.readUnsignedByte()) // RL=4
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID=10
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        assertEquals(0x00u, buf.readUnsignedByte()) // SUCCESS
    }

    @Test
    fun unsubackDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(6)
        buf.writeUByte(0xB0u); buf.writeUByte(0x04u) // type=11, RL=4
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u) // props len=0
        buf.writeUByte(0x00u) // SUCCESS
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<UnsubscribeAcknowledgment>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(listOf(ReasonCode.SUCCESS), packet.reasonCodes)
    }

    // ── DISCONNECT (§3.14) ─────────────────────────────────────────────────

    @Test
    fun disconnectNormalExactBytes() {
        val buf = packetBuffer {
            DisconnectNotification(DisconnectNotification.VariableHeader())
        }
        assertEquals(2, buf.remaining())
        assertEquals(0xE0u, buf.readUnsignedByte()) // type=14
        assertEquals(0x00u, buf.readUnsignedByte()) // RL=0
    }

    @Test
    fun disconnectWithReasonCodeExactBytes() {
        // UNSPECIFIED_ERROR, no properties → RL=2 (reason code + prop-length VBI=0)
        val buf = packetBuffer {
            DisconnectNotification(DisconnectNotification.VariableHeader(ReasonCode.UNSPECIFIED_ERROR))
        }
        assertEquals(4, buf.remaining())
        assertEquals(0xE0u, buf.readUnsignedByte()) // type=14
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x80u, buf.readUnsignedByte()) // UNSPECIFIED_ERROR = 0x80
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    @Test
    fun disconnectDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(2)
        buf.writeUByte(0xE0u); buf.writeUByte(0x00u)
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<DisconnectNotification>(packet)
        assertEquals(ReasonCode.NORMAL_DISCONNECTION, packet.variable.reasonCode)
    }

    @Test
    fun disconnectDecodeWithReasonCodeFromRawBytes() {
        // RL=2: reason code + property length
        val buf = BufferFactory.Default.allocate(4)
        buf.writeUByte(0xE0u); buf.writeUByte(0x02u)
        buf.writeUByte(0x80u) // UNSPECIFIED_ERROR
        buf.writeUByte(0x00u) // props len=0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<DisconnectNotification>(packet)
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, packet.variable.reasonCode)
    }

    @Test
    fun disconnectDecodeReasonCodeOnlyRL1() {
        // RL=1: just reason code, no property length (spec allows this)
        val buf = BufferFactory.Default.allocate(3)
        buf.writeUByte(0xE0u); buf.writeUByte(0x01u)
        buf.writeUByte(0x04u) // DISCONNECT_WITH_WILL_MESSAGE = 0x04
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<DisconnectNotification>(packet)
        assertEquals(ReasonCode.DISCONNECT_WITH_WILL_MESSAGE, packet.variable.reasonCode)
    }

    // ── AUTH (§3.15) ───────────────────────────────────────────────────────

    @Test
    fun authSuccessExactBytes() {
        val buf = packetBuffer {
            AuthenticationExchange(
                AuthenticationExchange.VariableHeader(
                    ReasonCode.SUCCESS,
                    AuthenticationExchange.VariableHeader.Properties(authentication = null),
                ),
            )
        }
        assertEquals(2, buf.remaining())
        assertEquals(0xF0u, buf.readUnsignedByte()) // type=15
        assertEquals(0x00u, buf.readUnsignedByte()) // RL=0
    }

    @Test
    fun authDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(2)
        buf.writeUByte(0xF0u); buf.writeUByte(0x00u)
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf)
        assertIs<AuthenticationExchange>(packet)
        assertEquals(ReasonCode.SUCCESS, packet.variable.reasonCode)
    }

    @Test
    fun authContinueExactBytes() {
        // CONTINUE_AUTHENTICATION = 0x18, no properties
        val buf = packetBuffer {
            AuthenticationExchange(
                AuthenticationExchange.VariableHeader(
                    ReasonCode.CONTINUE_AUTHENTICATION,
                    AuthenticationExchange.VariableHeader.Properties(authentication = null),
                ),
            )
        }
        assertEquals(4, buf.remaining())
        assertEquals(0xF0u, buf.readUnsignedByte()) // type=15
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x18u, buf.readUnsignedByte()) // CONTINUE_AUTHENTICATION = 0x18
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
    }

    // ── Backpatch: typed payload serialization ─────────────────────────────

    @Test
    fun publishTypedPayloadQos0BackpatchExactBytes() {
        // Typed publish: payload is Int (4 bytes), no properties
        val buf = PublishMessage<Int>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = 42,
            encodePayload = { buf, v -> buf.writeInt(v) },
            payloadSize = { Int.SIZE_BYTES },
        ).serialize(BufferFactory.Default)
        // topic "a" (3 bytes) + props VBI (1 byte) + payload (4 bytes) = 8 bytes remaining
        assertEquals(10, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        assertEquals(0x08u, buf.readUnsignedByte()) // RL=8
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        // payload: Int 42 = 0x0000002A
        assertEquals(0x00u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte())
        assertEquals(0x2Au, buf.readUnsignedByte())
    }

    @Test
    fun publishTypedPayloadQos1BackpatchExactBytes() {
        val buf = PublishMessage<Short>(
            fixed = PublishMessage.FixedHeader(qos = AT_LEAST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a"), packetIdentifier = 5),
            payload = 0x1234.toShort(),
            encodePayload = { buf, v -> buf.writeShort(v) },
            payloadSize = { Short.SIZE_BYTES },
        ).serialize(BufferFactory.Default)
        // topic "a" (3) + packetId (2) + props VBI (1) + payload (2) = 8
        assertEquals(10, buf.remaining())
        assertEquals(0x32u, buf.readUnsignedByte()) // type=3, QoS 1
        assertEquals(0x08u, buf.readUnsignedByte()) // RL=8
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x05u, buf.readUnsignedByte()) // packet ID=5
        assertEquals(0x00u, buf.readUnsignedByte()) // property length = 0
        assertEquals(0x12u, buf.readUnsignedByte()); assertEquals(0x34u, buf.readUnsignedByte()) // payload
    }

    @Test
    fun publishTypedPayloadMatchesReadBufferPayload() {
        // Verify backpatch produces identical bytes to standard ReadBuffer path
        val payloadBytes = BufferFactory.Default.allocate(4)
        payloadBytes.writeInt(42)
        payloadBytes.resetForRead()

        val readBufferPub = PublishMessage(topicName = "a", payload = payloadBytes)
            .serialize(BufferFactory.Default)
        readBufferPub.resetForRead()

        payloadBytes.position(0)

        val typedPub = PublishMessage<Int>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = 42,
            encodePayload = { buf, v -> buf.writeInt(v) },
            payloadSize = { Int.SIZE_BYTES },
        ).serialize(BufferFactory.Default)
        // typedPub is already read-ready (backpatch returns a slice)

        assertEquals(readBufferPub.remaining(), typedPub.remaining())
        while (readBufferPub.hasRemaining()) {
            assertEquals(readBufferPub.readUnsignedByte(), typedPub.readUnsignedByte())
        }
    }
}
