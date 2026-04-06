package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.validateMqttUTF8StringOrThrowWith
import com.ditchoom.mqtt3.controlpacket.wire.SubscribeWire
import com.ditchoom.mqtt3.controlpacket.wire.SubscribeWireCodec
import com.ditchoom.mqtt3.controlpacket.wire.SubscriptionWire
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscribeRequestTests {

    /**
     * Validates subscription payload bytes match MQTT 3.1.1 §3.8.3:
     * Each subscription is [2-byte topic length][UTF-8 topic][1-byte QoS].
     */
    @Test
    fun subscriptionPayloadBytesMatchSpec() {
        val wire = SubscribeWire(
            packetIdentifier = 1u,
            subscriptions = listOf(
                SubscriptionWire("a/b", AT_LEAST_ONCE.integerValue.toUByte()),
                SubscriptionWire("c/d", EXACTLY_ONCE.integerValue.toUByte()),
            ),
        )
        val buffer = BufferFactory.Default.allocate(16)
        SubscribeWireCodec.encode(buffer, wire)
        buffer.resetForRead()

        // Packet Identifier (2 bytes)
        assertEquals(0x00.toByte(), buffer.readByte()) // MSB
        assertEquals(0x01.toByte(), buffer.readByte()) // LSB = 1

        // Subscription 1: "a/b" QoS 1
        assertEquals(0x00.toByte(), buffer.readByte()) // Topic Length MSB
        assertEquals(0x03.toByte(), buffer.readByte()) // Topic Length LSB = 3
        assertEquals('a'.code.toByte(), buffer.readByte())
        assertEquals('/'.code.toByte(), buffer.readByte())
        assertEquals('b'.code.toByte(), buffer.readByte())
        assertEquals(0x01.toByte(), buffer.readByte()) // QoS = 1

        // Subscription 2: "c/d" QoS 2
        assertEquals(0x00.toByte(), buffer.readByte()) // Topic Length MSB
        assertEquals(0x03.toByte(), buffer.readByte()) // Topic Length LSB = 3
        assertEquals('c'.code.toByte(), buffer.readByte())
        assertEquals('/'.code.toByte(), buffer.readByte())
        assertEquals('d'.code.toByte(), buffer.readByte())
        assertEquals(0x02.toByte(), buffer.readByte()) // QoS = 2

        assertEquals(0, buffer.remaining())
    }

    /**
     * Validates encode → decode roundtrip produces identical wire object.
     */
    @Test
    fun subscribeWireRoundtrip() {
        val wire = SubscribeWire(
            packetIdentifier = 42u,
            subscriptions = listOf(
                SubscriptionWire("sensor/temp", AT_LEAST_ONCE.integerValue.toUByte()),
                SubscriptionWire("sensor/humidity", EXACTLY_ONCE.integerValue.toUByte()),
            ),
        )
        val buffer = BufferFactory.Default.allocate(64)
        SubscribeWireCodec.encode(buffer, wire)
        buffer.resetForRead()
        val decoded = SubscribeWireCodec.decode(buffer)
        assertEquals(wire.packetIdentifier, decoded.packetIdentifier)
        assertEquals(wire.subscriptions.size, decoded.subscriptions.size)
        wire.subscriptions.zip(decoded.subscriptions).forEach { (expected, actual) ->
            assertEquals(expected.topicFilter, actual.topicFilter)
            assertEquals(expected.requestedQos, actual.requestedQos)
        }
    }

    @Test
    fun packetIdentifierIsCorrect() {
        val buffer = BufferFactory.Default.allocate(100)
        val subscription = SubscribeRequest(10.toUShort(), "a/b", AT_MOST_ONCE)
        assertEquals(10, subscription.packetIdentifier)
        subscription.serialize(buffer)
        buffer.resetForRead()
        buffer.readByte()
        buffer.readByte()
        val packetIdentifier = buffer.readUnsignedShort().toInt()
        assertEquals(10, packetIdentifier)
    }

    /**
     * Full SUBSCRIBE packet byte validation per MQTT 3.1.1 §3.8:
     * Fixed header (0x82, remaining length) + Variable header (packet ID) + Payload (subscriptions)
     */
    @Test
    fun serialized() {
        val subscriptions =
            Subscription.fromOrThrow(
                listOf("a/b", "c/d"),
                listOf(AT_LEAST_ONCE, EXACTLY_ONCE),
            )
        val buffer = BufferFactory.Default.allocate(19)
        val request = SubscribeRequest(10, subscriptions)
        request.serialize(buffer)
        buffer.resetForRead()

        // Fixed header: packet type 8 (SUBSCRIBE) with reserved flags 0010 = 0x82
        assertEquals(0x82.toUByte(), buffer.readUnsignedByte())
        // Remaining length: 14 (2 packetId + 6 sub1 + 6 sub2)
        assertEquals(14.toUByte(), buffer.readUnsignedByte())

        // Variable header: Packet Identifier = 10
        assertEquals(0x00.toUByte(), buffer.readUnsignedByte()) // MSB
        assertEquals(0x0A.toUByte(), buffer.readUnsignedByte()) // LSB

        // Payload: subscription 1 "a/b" QoS 1
        assertEquals(0x00.toByte(), buffer.readByte())
        assertEquals(0x03.toByte(), buffer.readByte())
        assertEquals('a'.code.toByte(), buffer.readByte())
        assertEquals('/'.code.toByte(), buffer.readByte())
        assertEquals('b'.code.toByte(), buffer.readByte())
        assertEquals(0x01.toByte(), buffer.readByte())

        // Payload: subscription 2 "c/d" QoS 2
        assertEquals(0x00.toByte(), buffer.readByte())
        assertEquals(0x03.toByte(), buffer.readByte())
        assertEquals('c'.code.toByte(), buffer.readByte())
        assertEquals('/'.code.toByte(), buffer.readByte())
        assertEquals('d'.code.toByte(), buffer.readByte())
        assertEquals(0x02.toByte(), buffer.readByte())

        assertEquals(0, buffer.remaining())
    }

    @Test
    fun serializeDeserialize() {
        val subscribeRequest = SubscribeRequest(2, setOf(Subscription(TopicFilter.fromOrThrow("test"))))
        assertEquals(subscribeRequest.packetIdentifier, 2)
        val subs = subscribeRequest.subscriptions
        val firstSub = subs.first()
        val filter = firstSub.topicFilter
        val validated = validateMqttUTF8StringOrThrowWith(filter.toString())
        assertEquals(validated, "test")
        val buffer = BufferFactory.Default.allocate(11)
        subscribeRequest.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV4.from(buffer) as SubscribeRequest
        val subs1 = requestRead.subscriptions
        val firstSub1 = subs1.first()
        val filter1 = firstSub1.topicFilter
        val validated1 = validateMqttUTF8StringOrThrowWith(filter1.toString())
        assertEquals(validated1, "test")
    }
}
