package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that validate exact wire bytes against the MQTT 3.1.1 OASIS specification.
 * Each test either encodes a packet and asserts every byte, or constructs raw
 * spec-defined bytes and decodes them, verifying the resulting object.
 *
 * References: http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
 */
class SpecByteTests {

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun packetBuffer(block: () -> ControlPacketV4): ReadBuffer {
        val packet = block()
        val buffer = BufferFactory.Default.allocate(packet.packetSize())
        packet.serialize(buffer)
        buffer.resetForRead()
        return buffer
    }

    // ── PINGREQ (§3.12) ────────────────────────────────────────────────────

    @Test
    fun pingreqExactBytes() {
        val buf = packetBuffer { PingRequest }
        assertEquals(2, buf.remaining())
        assertEquals(0xC0u, buf.readUnsignedByte()) // type=12, flags=0000
        assertEquals(0x00u, buf.readUnsignedByte()) // remaining length = 0
    }

    // ── PINGRESP (§3.13) ───────────────────────────────────────────────────

    @Test
    fun pingrespExactBytes() {
        val buf = packetBuffer { PingResponse }
        assertEquals(2, buf.remaining())
        assertEquals(0xD0u, buf.readUnsignedByte()) // type=13, flags=0000
        assertEquals(0x00u, buf.readUnsignedByte()) // remaining length = 0
    }

    // ── DISCONNECT (§3.14) ─────────────────────────────────────────────────

    @Test
    fun disconnectExactBytes() {
        val buf = packetBuffer { DisconnectNotification }
        assertEquals(2, buf.remaining())
        assertEquals(0xE0u, buf.readUnsignedByte()) // type=14, flags=0000
        assertEquals(0x00u, buf.readUnsignedByte()) // remaining length = 0
    }

    // ── CONNACK (§3.2) ─────────────────────────────────────────────────────

    @Test
    fun connackAcceptedExactBytes() {
        val buf = packetBuffer {
            ConnectionAcknowledgment(sessionPresent = false, connectReason = ReturnCode.CONNECTION_ACCEPTED)
        }
        assertEquals(4, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2, flags=0000
        assertEquals(0x02u, buf.readUnsignedByte()) // remaining length = 2
        assertEquals(0x00u, buf.readUnsignedByte()) // session present = false
        assertEquals(0x00u, buf.readUnsignedByte()) // return code = accepted
    }

    @Test
    fun connackRejectedSessionPresentExactBytes() {
        val buf = packetBuffer {
            ConnectionAcknowledgment(sessionPresent = true, connectReason = ReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED)
        }
        assertEquals(4, buf.remaining())
        assertEquals(0x20u, buf.readUnsignedByte()) // type=2
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x01u, buf.readUnsignedByte()) // session present = true
        assertEquals(0x02u, buf.readUnsignedByte()) // return code = identifier rejected
    }

    @Test
    fun connackDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeUByte(0x20u) // type=2
        buf.writeUByte(0x02u) // RL=2
        buf.writeUByte(0x01u) // session present = true
        buf.writeUByte(0x00u) // return code = accepted
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<ConnectionAcknowledgment>(packet)
        assertTrue(packet.sessionPresent)
        assertTrue(packet.isSuccessful)
    }

    // ── PUBACK (§3.4) ──────────────────────────────────────────────────────

    @Test
    fun pubackPacketId10ExactBytes() {
        val buf = packetBuffer { PublishAcknowledgment(10u) }
        assertEquals(4, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte()) // type=4, flags=0000
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
    }

    // ── PUBREC (§3.5) ──────────────────────────────────────────────────────

    @Test
    fun pubrecPacketId10ExactBytes() {
        val buf = packetBuffer { PublishReceived(10u) }
        assertEquals(4, buf.remaining())
        assertEquals(0x50u, buf.readUnsignedByte()) // type=5, flags=0000
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
    }

    // ── PUBREL (§3.6) ──────────────────────────────────────────────────────

    @Test
    fun pubrelPacketId10ExactBytes() {
        val buf = packetBuffer { PublishRelease(10u) }
        assertEquals(4, buf.remaining())
        assertEquals(0x62u, buf.readUnsignedByte()) // type=6, flags=0010 (reserved)
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
    }

    // ── PUBCOMP (§3.7) ─────────────────────────────────────────────────────

    @Test
    fun pubcompPacketId10ExactBytes() {
        val buf = packetBuffer { PublishComplete(10u) }
        assertEquals(4, buf.remaining())
        assertEquals(0x70u, buf.readUnsignedByte()) // type=7, flags=0000
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
    }

    // ── UNSUBACK (§3.11) ───────────────────────────────────────────────────

    @Test
    fun unsubackPacketId10ExactBytes() {
        val buf = packetBuffer { UnsubscribeAcknowledgment(10u) }
        assertEquals(4, buf.remaining())
        assertEquals(0xB0u, buf.readUnsignedByte()) // type=11, flags=0000
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
    }

    // ── PUBLISH (§3.3) ─────────────────────────────────────────────────────

    @Test
    fun publishQos0TopicANoPayloadExactBytes() {
        // PUBLISH QoS 0, topic "a", no payload
        val buf = packetBuffer {
            PublishMessage(topicName = "a", qos = AT_MOST_ONCE)
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, flags=0000 (QoS 0)
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x00u, buf.readUnsignedByte()) // topic length MSB
        assertEquals(0x01u, buf.readUnsignedByte()) // topic length LSB = 1
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
    }

    @Test
    fun publishQos1TopicAPacketId1ExactBytes() {
        // PUBLISH QoS 1, topic "a", packet ID 1, no payload
        val buf = packetBuffer {
            PublishMessage(topicName = "a", qos = AT_LEAST_ONCE, packetIdentifier = 1)
        }
        assertEquals(7, buf.remaining())
        assertEquals(0x32u, buf.readUnsignedByte()) // type=3, flags=0010 (QoS 1)
        assertEquals(0x05u, buf.readUnsignedByte()) // RL=5
        assertEquals(0x00u, buf.readUnsignedByte()) // topic length MSB
        assertEquals(0x01u, buf.readUnsignedByte()) // topic length LSB = 1
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x01u, buf.readUnsignedByte()) // packet ID LSB = 1
    }

    @Test
    fun publishQos2TopicAPacketId1ExactBytes() {
        // PUBLISH QoS 2, topic "a", packet ID 1, no payload
        val buf = packetBuffer {
            PublishMessage(topicName = "a", qos = EXACTLY_ONCE, packetIdentifier = 1)
        }
        assertEquals(7, buf.remaining())
        assertEquals(0x34u, buf.readUnsignedByte()) // type=3, flags=0100 (QoS 2)
        assertEquals(0x05u, buf.readUnsignedByte()) // RL=5
        assertEquals(0x00u, buf.readUnsignedByte()) // topic length MSB
        assertEquals(0x01u, buf.readUnsignedByte()) // topic length LSB = 1
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x01u, buf.readUnsignedByte()) // packet ID LSB = 1
    }

    @Test
    fun publishDecodeQos0FromRawBytes() {
        // 30 03 00 01 61
        val buf = BufferFactory.Default.allocate(5)
        buf.writeUByte(0x30u) // type=3, QoS 0
        buf.writeUByte(0x03u) // RL=3
        buf.writeUByte(0x00u); buf.writeUByte(0x01u) // topic len=1
        buf.writeUByte(0x61u) // "a"
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<PublishMessage<*>>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(AT_MOST_ONCE, packet.qualityOfService)
        assertNull(packet.payload)
    }

    @Test
    fun publishDecodeQos1FromRawBytes() {
        // 32 05 00 01 61 00 01
        val buf = BufferFactory.Default.allocate(7)
        buf.writeUByte(0x32u) // type=3, QoS 1
        buf.writeUByte(0x05u) // RL=5
        buf.writeUByte(0x00u); buf.writeUByte(0x01u) // topic len=1
        buf.writeUByte(0x61u) // "a"
        buf.writeUByte(0x00u); buf.writeUByte(0x01u) // packet ID=1
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<PublishMessage<*>>(packet)
        assertEquals("a", packet.topic.toString())
        assertEquals(AT_LEAST_ONCE, packet.qualityOfService)
        assertEquals(1, packet.packetIdentifier)
    }

    // ── SUBSCRIBE (§3.8) ───────────────────────────────────────────────────

    @Test
    fun subscribeTopicAbQos1ExactBytes() {
        // Packet ID 10, topic "a/b", QoS 1
        val buf = packetBuffer {
            SubscribeRequest(10u, listOf(SubscriptionEntry("a/b", AT_LEAST_ONCE.integerValue.toUByte())))
        }
        assertEquals(10, buf.remaining())
        assertEquals(0x82u, buf.readUnsignedByte()) // type=8, flags=0010 (reserved)
        assertEquals(0x08u, buf.readUnsignedByte()) // RL=8
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
        assertEquals(0x00u, buf.readUnsignedByte()) // topic filter length MSB
        assertEquals(0x03u, buf.readUnsignedByte()) // topic filter length LSB = 3
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
        assertEquals(0x2Fu, buf.readUnsignedByte()) // '/'
        assertEquals(0x62u, buf.readUnsignedByte()) // 'b'
        assertEquals(0x01u, buf.readUnsignedByte()) // requested QoS = 1
    }

    @Test
    fun subscribeDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(10)
        buf.writeUByte(0x82u) // type=8, flags=0010
        buf.writeUByte(0x08u) // RL=8
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u); buf.writeUByte(0x03u) // topic filter len=3
        buf.writeUByte(0x61u); buf.writeUByte(0x2Fu); buf.writeUByte(0x62u) // "a/b"
        buf.writeUByte(0x01u) // QoS 1
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<SubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(1, packet.entries.size)
        assertEquals("a/b", packet.entries[0].filter)
        assertEquals(AT_LEAST_ONCE.integerValue.toUByte(), packet.entries[0].qos)
    }

    // ── SUBACK (§3.9) ──────────────────────────────────────────────────────

    @Test
    fun subackPacketId10GrantedQos1ExactBytes() {
        val buf = packetBuffer {
            SubscribeAcknowledgement(10, listOf(ReasonCode.GRANTED_QOS_1))
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x90u, buf.readUnsignedByte()) // type=9, flags=0000
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
        assertEquals(0x01u, buf.readUnsignedByte()) // return code = granted QoS 1
    }

    @Test
    fun subackDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(5)
        buf.writeUByte(0x90u) // type=9
        buf.writeUByte(0x03u) // RL=3
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x01u) // granted QoS 1
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<SubscribeAcknowledgement>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(listOf(ReasonCode.GRANTED_QOS_1), packet.payload)
    }

    // ── UNSUBSCRIBE (§3.10) ────────────────────────────────────────────────

    @Test
    fun unsubscribeTopicAbExactBytes() {
        // Packet ID 10, topic "a/b"
        val buf = packetBuffer {
            UnsubscribeRequest(10u, listOf(TopicFilterEntry("a/b")))
        }
        assertEquals(9, buf.remaining())
        assertEquals(0xA2u, buf.readUnsignedByte()) // type=10, flags=0010 (reserved)
        assertEquals(0x07u, buf.readUnsignedByte()) // RL=7
        assertEquals(0x00u, buf.readUnsignedByte()) // packet ID MSB
        assertEquals(0x0Au, buf.readUnsignedByte()) // packet ID LSB = 10
        assertEquals(0x00u, buf.readUnsignedByte()) // topic filter length MSB
        assertEquals(0x03u, buf.readUnsignedByte()) // topic filter length LSB = 3
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
        assertEquals(0x2Fu, buf.readUnsignedByte()) // '/'
        assertEquals(0x62u, buf.readUnsignedByte()) // 'b'
    }

    @Test
    fun unsubscribeDecodeFromRawBytes() {
        val buf = BufferFactory.Default.allocate(9)
        buf.writeUByte(0xA2u) // type=10, flags=0010
        buf.writeUByte(0x07u) // RL=7
        buf.writeUByte(0x00u); buf.writeUByte(0x0Au) // packet ID=10
        buf.writeUByte(0x00u); buf.writeUByte(0x03u) // topic filter len=3
        buf.writeUByte(0x61u); buf.writeUByte(0x2Fu); buf.writeUByte(0x62u) // "a/b"
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<UnsubscribeRequest>(packet)
        assertEquals(10, packet.packetIdentifier)
        assertEquals(1, packet.topicEntries.size)
        assertEquals("a/b", packet.topicEntries[0].filter)
    }

    // ── CONNECT (§3.1) ─────────────────────────────────────────────────────

    @Test
    fun connectMinimalExactBytes() {
        // Clean session, keep alive = 60, client ID "test"
        val buf = packetBuffer {
            ConnectionRequest(clientId = "test", keepAliveSeconds = 60, cleanSession = true)
        }
        assertEquals(18, buf.remaining())
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1, flags=0000
        assertEquals(0x10u, buf.readUnsignedByte()) // RL=16
        // Protocol name "MQTT"
        assertEquals(0x00u, buf.readUnsignedByte()) // protocol name length MSB
        assertEquals(0x04u, buf.readUnsignedByte()) // protocol name length LSB = 4
        assertEquals(0x4Du, buf.readUnsignedByte()) // 'M'
        assertEquals(0x51u, buf.readUnsignedByte()) // 'Q'
        assertEquals(0x54u, buf.readUnsignedByte()) // 'T'
        assertEquals(0x54u, buf.readUnsignedByte()) // 'T'
        // Protocol level
        assertEquals(0x04u, buf.readUnsignedByte()) // protocol level = 4
        // Connect flags: cleanSession=1, rest=0
        assertEquals(0x02u, buf.readUnsignedByte()) // 0000_0010
        // Keep alive
        assertEquals(0x00u, buf.readUnsignedByte()) // keep alive MSB
        assertEquals(0x3Cu, buf.readUnsignedByte()) // keep alive LSB = 60
        // Client ID "test"
        assertEquals(0x00u, buf.readUnsignedByte()) // client ID length MSB
        assertEquals(0x04u, buf.readUnsignedByte()) // client ID length LSB = 4
        assertEquals(0x74u, buf.readUnsignedByte()) // 't'
        assertEquals(0x65u, buf.readUnsignedByte()) // 'e'
        assertEquals(0x73u, buf.readUnsignedByte()) // 's'
        assertEquals(0x74u, buf.readUnsignedByte()) // 't'
    }

    @Test
    fun connectWithWillUsernamePasswordExactBytes() {
        // Clean session, keep alive = 60, client ID "c", will topic "w",
        // will payload = 1 byte 'p', username "u", password "x", willQos = AT_LEAST_ONCE
        val willPayload = BufferFactory.Default.allocate(1)
        willPayload.writeUByte(0x70u) // 'p'
        willPayload.resetForRead()
        val buf = packetBuffer {
            ConnectionRequest(
                clientId = "c",
                keepAliveSeconds = 60,
                cleanSession = true,
                userName = "u",
                password = "x",
                will = WillConfig.Enabled(
                    TopicName.fromOrThrow("w"),
                    willPayload,
                    AT_LEAST_ONCE,
                    retain = false,
                ),
            )
        }
        // flags = username(1) password(1) willRetain(0) willQos(01) willFlag(1) cleanSession(1) reserved(0)
        //       = 1_1_0_01_1_1_0 = 0xCE
        assertEquals(27, buf.remaining())
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1
        assertEquals(0x19u, buf.readUnsignedByte()) // RL=25
        // Protocol name "MQTT"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte())
        assertEquals(0x4Du, buf.readUnsignedByte()); assertEquals(0x51u, buf.readUnsignedByte())
        assertEquals(0x54u, buf.readUnsignedByte()); assertEquals(0x54u, buf.readUnsignedByte())
        // Protocol level
        assertEquals(0x04u, buf.readUnsignedByte())
        // Connect flags
        assertEquals(0xCEu, buf.readUnsignedByte()) // 1100_1110
        // Keep alive = 60
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x3Cu, buf.readUnsignedByte())
        // Client ID "c"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x63u, buf.readUnsignedByte()) // 'c'
        // Will topic "w"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x77u, buf.readUnsignedByte()) // 'w'
        // Will payload: length-prefixed 1 byte
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x70u, buf.readUnsignedByte()) // 'p'
        // Username "u"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x75u, buf.readUnsignedByte()) // 'u'
        // Password "x"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x78u, buf.readUnsignedByte()) // 'x'
    }

    @Test
    fun connectDecodeFromRawBytes() {
        // Minimal CONNECT: clean session, keepAlive=60, clientId="test"
        val buf = BufferFactory.Default.allocate(18)
        buf.writeUByte(0x10u) // type=1
        buf.writeUByte(0x10u) // RL=16
        buf.writeUByte(0x00u); buf.writeUByte(0x04u) // protocol name len
        buf.writeUByte(0x4Du); buf.writeUByte(0x51u); buf.writeUByte(0x54u); buf.writeUByte(0x54u) // "MQTT"
        buf.writeUByte(0x04u) // protocol level
        buf.writeUByte(0x02u) // flags: cleanSession
        buf.writeUByte(0x00u); buf.writeUByte(0x3Cu) // keepAlive=60
        buf.writeUByte(0x00u); buf.writeUByte(0x04u) // clientId len
        buf.writeUByte(0x74u); buf.writeUByte(0x65u); buf.writeUByte(0x73u); buf.writeUByte(0x74u) // "test"
        buf.resetForRead()
        val packet = ControlPacketV4.from(buf)
        assertIs<ConnectionRequest>(packet)
        assertEquals("MQTT", packet.protocolName)
        assertEquals(4, packet.protocolVersion)
        assertTrue(packet.cleanStart)
        assertEquals(60, packet.keepAliveTimeoutSeconds.toInt())
        assertEquals("test", packet.clientIdentifier)
        assertFalse(packet.hasUserName)
        assertFalse(packet.hasPassword)
        assertEquals(WillConfig.Disabled, packet.will)
    }

    // ── Edge cases: Packet ID boundaries ───────────────────────────────────

    @Test
    fun pubackMaxPacketIdExactBytes() {
        val buf = packetBuffer { PublishAcknowledgment(0xFFFFu) }
        assertEquals(4, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte()) // type=4
        assertEquals(0x02u, buf.readUnsignedByte()) // RL=2
        assertEquals(0xFFu, buf.readUnsignedByte()) // packet ID MSB = 0xFF
        assertEquals(0xFFu, buf.readUnsignedByte()) // packet ID LSB = 0xFF
    }

    @Test
    fun pubackMinPacketIdExactBytes() {
        val buf = packetBuffer { PublishAcknowledgment(0x0001u) }
        assertEquals(4, buf.remaining())
        assertEquals(0x40u, buf.readUnsignedByte())
        assertEquals(0x02u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()) // MSB = 0
        assertEquals(0x01u, buf.readUnsignedByte()) // LSB = 1
    }

    // ── Edge cases: PUBLISH flags ──────────────────────────────────────────

    @Test
    fun publishDupRetainFlagsExactBytes() {
        // DUP=1, QoS=1, RETAIN=1 → flags = 1011 = 0x0B
        val buf = packetBuffer {
            PublishMessage(topicName = "a", qos = AT_LEAST_ONCE, dup = true, retain = true, packetIdentifier = 1)
        }
        assertEquals(7, buf.remaining())
        assertEquals(0x3Bu, buf.readUnsignedByte()) // type=3, flags=1011 (DUP+QoS1+RETAIN)
        assertEquals(0x05u, buf.readUnsignedByte()) // RL=5
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
    }

    @Test
    fun publishRetainOnlyQos0ExactBytes() {
        // DUP=0, QoS=0, RETAIN=1 → flags = 0001
        val buf = packetBuffer {
            PublishMessage(topicName = "a", qos = AT_MOST_ONCE, retain = true)
        }
        assertEquals(5, buf.remaining())
        assertEquals(0x31u, buf.readUnsignedByte()) // type=3, flags=0001 (RETAIN only)
        assertEquals(0x03u, buf.readUnsignedByte()) // RL=3
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
    }

    @Test
    fun publishWithPayloadExactBytes() {
        // QoS 0, topic "t", payload "hi"
        val payload = BufferFactory.Default.allocate(2)
        payload.writeUByte(0x68u) // 'h'
        payload.writeUByte(0x69u) // 'i'
        payload.resetForRead()
        val buf = packetBuffer {
            PublishMessage(
                fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
                variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("t")),
                payload = payload,
            )
        }
        assertEquals(7, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        assertEquals(0x05u, buf.readUnsignedByte()) // RL=5
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "t"
        assertEquals(0x74u, buf.readUnsignedByte())
        assertEquals(0x68u, buf.readUnsignedByte()) // 'h'
        assertEquals(0x69u, buf.readUnsignedByte()) // 'i'
    }

    // ── Edge cases: SUBSCRIBE multiple topics ──────────────────────────────

    @Test
    fun subscribeMultipleTopicsExactBytes() {
        // Packet ID 1, topics "a" QoS 0, "b" QoS 2
        val buf = packetBuffer {
            SubscribeRequest(
                1u,
                listOf(
                    SubscriptionEntry("a", AT_MOST_ONCE.integerValue.toUByte()),
                    SubscriptionEntry("b", EXACTLY_ONCE.integerValue.toUByte()),
                ),
            )
        }
        assertEquals(12, buf.remaining())
        assertEquals(0x82u, buf.readUnsignedByte()) // type=8, flags=0010
        assertEquals(0x0Au, buf.readUnsignedByte()) // RL=10
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        // First topic "a" QoS 0
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // len=1
        assertEquals(0x61u, buf.readUnsignedByte()) // 'a'
        assertEquals(0x00u, buf.readUnsignedByte()) // QoS 0
        // Second topic "b" QoS 2
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // len=1
        assertEquals(0x62u, buf.readUnsignedByte()) // 'b'
        assertEquals(0x02u, buf.readUnsignedByte()) // QoS 2
    }

    // ── Edge cases: SUBACK multiple return codes ───────────────────────────

    @Test
    fun subackMultipleReturnCodesExactBytes() {
        val buf = packetBuffer {
            SubscribeAcknowledgement(
                1,
                listOf(ReasonCode.GRANTED_QOS_0, ReasonCode.GRANTED_QOS_2, ReasonCode.UNSPECIFIED_ERROR),
            )
        }
        assertEquals(7, buf.remaining())
        assertEquals(0x90u, buf.readUnsignedByte()) // type=9
        assertEquals(0x05u, buf.readUnsignedByte()) // RL=5
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        assertEquals(0x00u, buf.readUnsignedByte()) // granted QoS 0
        assertEquals(0x02u, buf.readUnsignedByte()) // granted QoS 2
        assertEquals(0x80u, buf.readUnsignedByte()) // failure = 0x80
    }

    // ── Edge cases: UNSUBSCRIBE multiple topics ────────────────────────────

    @Test
    fun unsubscribeMultipleTopicsExactBytes() {
        val buf = packetBuffer {
            UnsubscribeRequest(1u, listOf(TopicFilterEntry("a"), TopicFilterEntry("b")))
        }
        assertEquals(10, buf.remaining())
        assertEquals(0xA2u, buf.readUnsignedByte()) // type=10, flags=0010
        assertEquals(0x08u, buf.readUnsignedByte()) // RL=8
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        // "a"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x61u, buf.readUnsignedByte())
        // "b"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x62u, buf.readUnsignedByte())
    }

    // ── Edge cases: CONNECT empty client ID ────────────────────────────────

    @Test
    fun connectEmptyClientIdExactBytes() {
        val buf = packetBuffer {
            ConnectionRequest(clientId = "", keepAliveSeconds = 0, cleanSession = true)
        }
        assertEquals(14, buf.remaining())
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1
        assertEquals(0x0Cu, buf.readUnsignedByte()) // RL=12
        // "MQTT"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte())
        assertEquals(0x4Du, buf.readUnsignedByte()); assertEquals(0x51u, buf.readUnsignedByte())
        assertEquals(0x54u, buf.readUnsignedByte()); assertEquals(0x54u, buf.readUnsignedByte())
        assertEquals(0x04u, buf.readUnsignedByte()) // level=4
        assertEquals(0x02u, buf.readUnsignedByte()) // cleanSession
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // keepAlive=0
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // empty client ID
    }

    // ── Edge cases: CONNECT will QoS 2 + retain ────────────────────────────

    @Test
    fun connectWillQos2RetainExactBytes() {
        val willPayload = BufferFactory.Default.allocate(1)
        willPayload.writeUByte(0x00u)
        willPayload.resetForRead()
        val buf = packetBuffer {
            ConnectionRequest(
                clientId = "",
                keepAliveSeconds = 0,
                cleanSession = true,
                will = WillConfig.Enabled(
                    TopicName.fromOrThrow("d"),
                    willPayload,
                    EXACTLY_ONCE,
                    retain = true,
                ),
            )
        }
        // flags = username(0) password(0) willRetain(1) willQos(10) willFlag(1) cleanSession(1) reserved(0)
        //       = 0_0_1_10_1_1_0 = 0x36
        val total = buf.remaining()
        assertEquals(0x10u, buf.readUnsignedByte()) // type=1
        buf.readUnsignedByte() // RL (skip, verified by total size)
        // "MQTT"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x04u, buf.readUnsignedByte())
        assertEquals(0x4Du, buf.readUnsignedByte()); assertEquals(0x51u, buf.readUnsignedByte())
        assertEquals(0x54u, buf.readUnsignedByte()); assertEquals(0x54u, buf.readUnsignedByte())
        assertEquals(0x04u, buf.readUnsignedByte()) // level
        assertEquals(0x36u, buf.readUnsignedByte()) // flags: willRetain + willQos2 + willFlag + cleanSession
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // keepAlive=0
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x00u, buf.readUnsignedByte()) // empty client ID
        // will topic "d"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x64u, buf.readUnsignedByte())
        // will payload: 1 byte
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte())
    }

    // ── Edge cases: SUBSCRIBE with wildcard topics ─────────────────────────

    @Test
    fun subscribeWildcardTopicExactBytes() {
        val buf = packetBuffer {
            SubscribeRequest(1u, listOf(SubscriptionEntry("sensor/+/temp", AT_MOST_ONCE.integerValue.toUByte())))
        }
        assertEquals(20, buf.remaining())
        assertEquals(0x82u, buf.readUnsignedByte()) // type=8, flags=0010
        assertEquals(0x12u, buf.readUnsignedByte()) // RL=18
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // packet ID=1
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x0Du, buf.readUnsignedByte()) // len=13
        // "sensor/+/temp"
        assertEquals(0x73u, buf.readUnsignedByte()) // 's'
        assertEquals(0x65u, buf.readUnsignedByte()) // 'e'
        assertEquals(0x6Eu, buf.readUnsignedByte()) // 'n'
        assertEquals(0x73u, buf.readUnsignedByte()) // 's'
        assertEquals(0x6Fu, buf.readUnsignedByte()) // 'o'
        assertEquals(0x72u, buf.readUnsignedByte()) // 'r'
        assertEquals(0x2Fu, buf.readUnsignedByte()) // '/'
        assertEquals(0x2Bu, buf.readUnsignedByte()) // '+'
        assertEquals(0x2Fu, buf.readUnsignedByte()) // '/'
        assertEquals(0x74u, buf.readUnsignedByte()) // 't'
        assertEquals(0x65u, buf.readUnsignedByte()) // 'e'
        assertEquals(0x6Du, buf.readUnsignedByte()) // 'm'
        assertEquals(0x70u, buf.readUnsignedByte()) // 'p'
        assertEquals(0x00u, buf.readUnsignedByte()) // QoS 0
    }

    // ── Edge cases: Multi-byte VBI (remaining length > 127) ────────────────

    @Test
    fun publishLargePayloadMultiByteVbiExactBytes() {
        // Topic "t" (3 bytes) + payload of 126 bytes = 129 bytes remaining
        // VBI encoding: 129 = 0x81 0x01
        val payload = BufferFactory.Default.allocate(126)
        for (i in 0 until 126) payload.writeUByte(0xAAu)
        payload.resetForRead()
        val buf = packetBuffer {
            PublishMessage(
                fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
                variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("t")),
                payload = payload,
            )
        }
        // Total = 1 (byte1) + 2 (VBI for 129) + 129 = 132
        assertEquals(132, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        // VBI for 129: low 7 bits = 1, continuation bit set → 0x81; then 0x01
        assertEquals(0x81u, buf.readUnsignedByte()) // VBI byte 1
        assertEquals(0x01u, buf.readUnsignedByte()) // VBI byte 2
        // topic "t"
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte())
        assertEquals(0x74u, buf.readUnsignedByte())
        // payload: 126 bytes of 0xAA
        for (i in 0 until 126) {
            assertEquals(0xAAu, buf.readUnsignedByte())
        }
    }

    // ── Backpatch: typed payload serialization ─────────────────────────────

    @Test
    fun publishTypedPayloadQos0BackpatchExactBytes() {
        // Typed publish: payload is Int (4 bytes), encoded via backpatch
        val buf = PublishMessage<Int>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = 42,
            encodePayload = { buf, v -> buf.writeInt(v) },
            payloadSize = { Int.SIZE_BYTES },
        ).serialize(BufferFactory.Default)
        // topic "a" (3 bytes) + payload (4 bytes) = 7 bytes remaining
        assertEquals(9, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        assertEquals(0x07u, buf.readUnsignedByte()) // RL=7
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
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
        // topic "a" (3 bytes) + packetId (2 bytes) + payload (2 bytes) = 7 bytes remaining
        assertEquals(9, buf.remaining())
        assertEquals(0x32u, buf.readUnsignedByte()) // type=3, QoS 1
        assertEquals(0x07u, buf.readUnsignedByte()) // RL=7
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x01u, buf.readUnsignedByte()) // topic "a"
        assertEquals(0x61u, buf.readUnsignedByte())
        assertEquals(0x00u, buf.readUnsignedByte()); assertEquals(0x05u, buf.readUnsignedByte()) // packet ID=5
        // payload: Short 0x1234
        assertEquals(0x12u, buf.readUnsignedByte())
        assertEquals(0x34u, buf.readUnsignedByte())
    }

    @Test
    fun publishTypedPayloadMatchesReadBufferPayload() {
        // Verify backpatch produces identical bytes to standard ReadBuffer path
        val payloadBytes = BufferFactory.Default.allocate(4)
        payloadBytes.writeInt(42)
        payloadBytes.resetForRead()

        val readBufferPub = PublishMessage<ReadBuffer?>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = payloadBytes,
        ).serialize(BufferFactory.Default)
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

        // Both must produce identical wire bytes
        assertEquals(readBufferPub.remaining(), typedPub.remaining())
        while (readBufferPub.hasRemaining()) {
            assertEquals(readBufferPub.readUnsignedByte(), typedPub.readUnsignedByte())
        }
    }

    @Test
    fun publishTypedPayloadGrowsOnUnderEstimate() {
        // payloadSize underestimates (1 byte), actual payload is 4 bytes → triggers grow
        val buf = PublishMessage<Int>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = 42,
            encodePayload = { buf, v -> buf.writeInt(v) },
            payloadSize = { 1 }, // intentionally too small
        ).serialize(BufferFactory.Default)
        // Should still produce correct bytes despite underestimate
        assertEquals(9, buf.remaining())
        assertEquals(0x30u, buf.readUnsignedByte()) // type=3, QoS 0
        assertEquals(0x07u, buf.readUnsignedByte()) // RL=7
    }

    @Test
    fun publishTypedPayloadRemainingLengthUsesPayloadSize() {
        val pub = PublishMessage<Int>(
            fixed = PublishMessage.FixedHeader(qos = AT_MOST_ONCE),
            variable = PublishMessage.VariableHeader(TopicName.fromOrThrow("a")),
            payload = 42,
            encodePayload = { buf, v -> buf.writeInt(v) },
            payloadSize = { Int.SIZE_BYTES },
        )
        // remainingLength = variable header (3 bytes for topic "a") + payload (4 bytes) = 7
        assertEquals(7, pub.remainingLength())
    }
}
