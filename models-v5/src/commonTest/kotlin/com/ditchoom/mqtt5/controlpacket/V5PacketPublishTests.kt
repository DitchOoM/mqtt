package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
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
 * Spec-conformance round-trip tests for the new `ControlPacketV5.Publish<P>` sealed-tree variant.
 *
 * Validates the wire format directly via `ControlPacketV5PublishCodec.encode/decode`, exercising:
 *  - QoS 0/1/2 — packet identifier presence rule (§3.3.2.2 / §2.3.1)
 *  - dup/retain header bits (§3.3.1.1, §3.3.1.3)
 *  - empty vs non-empty property bag (§3.3.2.3)
 *  - typed PublishMessage interface accessors derived from the fixed-header byte
 *  - validate() spec-violation paths
 *  - expectedResponse() shape per QoS
 *
 * Locks in the round-trip shape for `ControlPacketV5.Publish<P>`.
 */
class V5PacketPublishTests {
    // Phase A intermediary: PUBLISH payload routes through com.ditchoom.mqtt.controlpacket.OpaquePublishPayload.
    // Round-trip via the parent sealed-tree codec (ControlPacketV5Codec) — the variant codec
    // shape changed under directional-codec migration and is no longer directly callable
    // for round-trip testing without the dispatcher's header forwarding.
    private fun roundTrip(
        value: ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>,
    ): ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload> {
        val encoded = encodeToReadBufferV5(value)
        @Suppress("UNCHECKED_CAST")
        return decodeV5(encoded) as ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>
    }

    private fun makePayload(text: String): ReadBuffer {
        if (text.isEmpty()) return ReadBuffer.EMPTY_BUFFER
        return text.toReadBuffer()
    }

    @Test
    fun qos0NoPacketIdRoundTrip() {
        val original =
            ControlPacketV5.Publish.ofRaw(
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
        assertEquals("hello", decoded.payload.asUtf8String())
    }

    @Test
    fun qos1PacketIdRoundTrip() {
        val original =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t/a"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 42,
                payload = makePayload("body"),
            )
        val decoded = roundTrip(original)
        assertEquals(AT_LEAST_ONCE, decoded.qualityOfService)
        assertEquals(42u.toUShort(), decoded.packetId)
        assertEquals(42, decoded.packetIdentifier)
        assertEquals("body", decoded.payload.asUtf8String())
    }

    @Test
    fun qos2PacketIdRoundTrip() {
        val original =
            ControlPacketV5.Publish.ofRaw(
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
            ControlPacketV5.Publish.ofRaw(
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
            ControlPacketV5.Publish.ofRaw(
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
            ControlPacketV5.Publish.ofRaw(
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
            ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>(
                header = MqttFixedHeader(0x30u),
                topicName = "t/a",
                packetId = 1u, // QoS 0 with packet id — invalid per [MQTT-2.3.1-1]
                properties = emptyList(),
                payload = opaquePublishPayloadOf(""),
            )
        val err = invalid.validate()
        assertTrue(err != null && err.message?.contains("MQTT-2.3.1-1") == true)
    }

    @Test
    fun validateRejectsQosGreaterZeroWithoutPacketId() {
        val invalid =
            ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>(
                header = MqttFixedHeader(0x32u), // QoS 1
                topicName = "t/a",
                packetId = null,
                properties = emptyList(),
                payload = opaquePublishPayloadOf(""),
            )
        val err = invalid.validate()
        assertTrue(err != null && err.message?.contains("MQTT-2.3.1-5") == true)
    }

    @Test
    fun reservedQos3Rejected() {
        // QoS bits = 11 → spec §3.3.1-4 malformed
        assertFailsWith<com.ditchoom.mqtt.MalformedPacketException> {
            ControlPacketV5.Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>(
                header = MqttFixedHeader(0x36u),
                topicName = "t/a",
                packetId = null,
                properties = emptyList(),
                payload = opaquePublishPayloadOf(""),
            )
        }
    }

    @Test
    fun expectedResponseShapeMatchesQos() {
        val q0 =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertNull(q0.expectedResponse())

        val q1 =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertIs<ControlPacketV5.PubAck>(q1.expectedResponse())

        val q2 =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = EXACTLY_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertIs<ControlPacketV5.PubRec>(q2.expectedResponse())
    }

    @Test
    fun setDupFlagFlipsBit() {
        val q1 =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_LEAST_ONCE,
                packetIdentifier = 1,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        assertEquals(false, q1.dup)
        val redup = q1.setDupFlagNewPubMessage()
        assertIs<ControlPacketV5.Publish<*>>(redup)
        assertEquals(true, redup.dup)
        assertEquals(AT_LEAST_ONCE, redup.qualityOfService)
        // Other fields preserved
        assertEquals(1, redup.packetIdentifier)
    }

    @Test
    fun maybeCopyWithNewPacketIdentifierAtQos0Returnsself() {
        val q0 =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                qos = AT_MOST_ONCE,
                payload = ReadBuffer.EMPTY_BUFFER,
            )
        val copy = q0.maybeCopyWithNewPacketIdentifier(99)
        // QoS 0 disallows packet id; the copy must retain that.
        assertNull((copy as ControlPacketV5.Publish<*>).packetId)
    }

    @Test
    fun userPropertyDuplicatesAllowed() {
        // §3.3.2.3.5: User Property can repeat. Round-trip preserves order.
        val original =
            ControlPacketV5.Publish.ofRaw(
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
