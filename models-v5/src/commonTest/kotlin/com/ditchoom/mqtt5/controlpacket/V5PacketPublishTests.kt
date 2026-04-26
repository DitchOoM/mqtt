package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec-conformance round-trip tests for the new `V5Packet.Publish<P>` sealed-tree variant.
 *
 * Validates the wire format directly via `V5PacketPublishCodec.encode/decode`, exercising:
 *  - QoS 0/1/2 — packet identifier presence rule (§3.3.2.2 / §2.3.1)
 *  - dup/retain header bits (§3.3.1.1, §3.3.1.3)
 *  - empty vs non-empty property bag (§3.3.2.3)
 *  - typed PublishMessage interface accessors derived from the fixed-header byte
 *  - validate() spec-violation paths
 *  - expectedResponse() shape per QoS
 *
 * Independent from the legacy `PublishMessageV5` round-trip path; locks in the new shape.
 */
class V5PacketPublishTests {
    private fun roundTrip(value: V5Packet.Publish<ReadBuffer>): V5Packet.Publish<ReadBuffer> {
        val buf = BufferFactory.Default.allocate(value.remainingLength() + 8, ByteOrder.BIG_ENDIAN)
        // V5PacketPublishCodec.encode writes byte1 (the fixed-header byte) into the buffer
        // because PUBLISH carries `header: MqttFixedHeader` as its first field. The matching
        // decode reads `header` from context (set by the dispatcher) and skips byte1 from the
        // wire — so the round-trip helper consumes byte1 explicitly here, then hands the
        // remaining body bytes to the variant codec along with the header in context.
        V5PacketPublishCodec.encode(buf, value) { wbuf, p -> wbuf.write(p) }
        buf.resetForRead()
        val byte1 = MqttFixedHeader(buf.readUnsignedByte())
        val ctx = com.ditchoom.buffer.codec.DecodeContext.Empty.with(V5PacketCodec.DiscriminatorKey, byte1)
        return V5PacketPublishCodec.decode<ReadBuffer>(buf, ctx) { slice ->
            slice.readBytes(slice.remaining())
        }
    }

    private fun makePayload(text: String): ReadBuffer {
        if (text.isEmpty()) return ReadBuffer.EMPTY_BUFFER
        return text.toReadBuffer()
    }

    @Test
    fun qos0NoPacketIdRoundTrip() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_MOST_ONCE,
                payload = makePayload("hello"),
            )
        val decoded = roundTrip(original)
        assertEquals(original.topicName, decoded.topicName)
        assertEquals(AT_MOST_ONCE, decoded.qualityOfService)
        assertEquals(NO_PACKET_ID, decoded.packetIdentifier)
        assertNull(decoded.packetId)
        assertEquals(false, decoded.dup)
        assertEquals(false, decoded.retain)
        assertEquals("hello", decoded.payload.readString(decoded.payload.remaining()))
    }

    @Test
    fun qos1PacketIdRoundTrip() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 42,
                payload = makePayload("body"),
            )
        val decoded = roundTrip(original)
        assertEquals(AT_LEAST_ONCE, decoded.qualityOfService)
        assertEquals(42u.toUShort(), decoded.packetId)
        assertEquals(42, decoded.packetIdentifier)
        assertEquals("body", decoded.payload.readString(decoded.payload.remaining()))
    }

    @Test
    fun qos2PacketIdRoundTrip() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = EXACTLY_ONCE,
                packetIdentifier = 0xFFFF,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val decoded = roundTrip(original)
        assertEquals(EXACTLY_ONCE, decoded.qualityOfService)
        assertEquals(0xFFFFu.toUShort(), decoded.packetId)
    }

    @Test
    fun dupAndRetainHeaderBitsRoundTrip() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 1,
                dup = true,
                retain = true,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val decoded = roundTrip(original)
        assertEquals(true, decoded.dup)
        assertEquals(true, decoded.retain)
        assertEquals(AT_LEAST_ONCE, decoded.qualityOfService)
    }

    @Test
    fun emptyPropertyBagDecodesAsEmpty() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_MOST_ONCE,
                payload = makePayload("p"),
                properties = PublishProperties(),
            )
        val decoded = roundTrip(original)
        assertTrue(decoded.properties.isEmpty())
        assertTrue(decoded.typedProperties.userProperty.isEmpty())
    }

    @Test
    fun nonEmptyPropertyBagRoundTrip() {
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 7,
                payload = makePayload("p"),
                properties =
                    PublishProperties(
                        contentType = "application/json",
                        userProperty = listOf("k1" to "v1", "k2" to "v2"),
                    ),
            )
        val decoded = roundTrip(original)
        assertEquals("application/json", decoded.typedProperties.contentType)
        assertEquals(2, decoded.typedProperties.userProperty.size)
        assertEquals("k1" to "v1", decoded.typedProperties.userProperty[0])
        assertEquals("k2" to "v2", decoded.typedProperties.userProperty[1])
    }

    @Test
    fun validateRejectsQos0WithPacketId() {
        val invalid =
            V5Packet.Publish<ReadBuffer>(
                header = MqttFixedHeader(0x30u),
                topicName = "t/a",
                packetId = 1u, // QoS 0 with packet id — invalid per [MQTT-2.3.1-1]
                properties = emptyList(),
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val err = invalid.validate()
        assertTrue(err != null && err.message?.contains("MQTT-2.3.1-1") == true)
    }

    @Test
    fun validateRejectsQosGreaterZeroWithoutPacketId() {
        val invalid =
            V5Packet.Publish<ReadBuffer>(
                header = MqttFixedHeader(0x32u), // QoS 1
                topicName = "t/a",
                packetId = null,
                properties = emptyList(),
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val err = invalid.validate()
        assertTrue(err != null && err.message?.contains("MQTT-2.3.1-5") == true)
    }

    @Test
    fun reservedQos3Rejected() {
        // QoS bits = 11 → spec §3.3.1-4 malformed
        assertFailsWith<com.ditchoom.mqtt.MalformedPacketException> {
            V5Packet.Publish<ReadBuffer>(
                header = MqttFixedHeader(0x36u),
                topicName = "t/a",
                packetId = null,
                properties = emptyList(),
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        }
    }

    @Test
    fun expectedResponseShapeMatchesQos() {
        val q0 =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertNull(q0.expectedResponse())

        val q1 =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertIs<V5Packet.PubAck>(q1.expectedResponse())

        val q2 =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertIs<V5Packet.PubRec>(q2.expectedResponse())
    }

    @Test
    fun setDupFlagFlipsBit() {
        val q1 =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertEquals(false, q1.dup)
        val redup = q1.setDupFlagNewPubMessage()
        assertIs<V5Packet.Publish<*>>(redup)
        assertEquals(true, redup.dup)
        assertEquals(AT_LEAST_ONCE, redup.qualityOfService)
        // Other fields preserved
        assertEquals(1, redup.packetIdentifier)
    }

    @Test
    fun maybeCopyWithNewPacketIdentifierAtQos0Returnsself() {
        val q0 =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val copy = q0.maybeCopyWithNewPacketIdentifier(99)
        // QoS 0 disallows packet id; the copy must retain that.
        assertNull((copy as V5Packet.Publish<*>).packetId)
    }

    @Test
    fun userPropertyDuplicatesAllowed() {
        // §3.3.2.3.5: User Property can repeat. Round-trip preserves order.
        val original =
            V5Packet.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_MOST_ONCE,
                payload = ReadBuffer.EMPTY_BUFFER,
                properties =
                    PublishProperties(
                        userProperty = listOf("k" to "v1", "k" to "v2", "k" to "v3"),
                    ),
            )
        val decoded = roundTrip(original)
        assertEquals(3, decoded.typedProperties.userProperty.size)
        assertEquals(listOf("k" to "v1", "k" to "v2", "k" to "v3"), decoded.typedProperties.userProperty)
        // Underlying List<MqttProperty> still has duplicates as separate entries.
        assertEquals(3, decoded.properties.count { it is UserProperty })
    }
}
