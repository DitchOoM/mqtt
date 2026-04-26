package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round-trip and byte-level tests for every MQTT v5 Property subclass.
 * Each test: construct -> encode to buffer -> decode -> assertEquals.
 * Also verifies sizeOf matches the actual bytes written.
 */
class PropertyRoundTripTests {
    // ── Boolean properties (identifier + 1 byte, size=2) ────────────────────

    @Test
    fun payloadFormatIndicatorTrue() {
        val prop = PayloadFormatIndicator(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x01.toByte(), bytes[0], "identifier byte")
        assertEquals(0x01.toByte(), bytes[1], "true -> 0x01")
        assertIs<PayloadFormatIndicator>(decoded)
        assertTrue(decoded.isUtf8)
    }

    @Test
    fun payloadFormatIndicatorFalse() {
        val prop = PayloadFormatIndicator(false)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "false -> 0x00")
        assertIs<PayloadFormatIndicator>(decoded)
        assertEquals(false, decoded.isUtf8)
    }

    @Test
    fun requestProblemInformation() {
        val prop = RequestProblemInformation(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x17.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertIs<RequestProblemInformation>(decoded)
        assertTrue(decoded.enabled)
    }

    @Test
    fun requestResponseInformation() {
        val prop = RequestResponseInformation(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x19.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertIs<RequestResponseInformation>(decoded)
        assertTrue(decoded.enabled)
    }

    @Test
    fun retainAvailable() {
        val prop = RetainAvailable(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x25.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertIs<RetainAvailable>(decoded)
        assertTrue(decoded.supported)
    }

    @Test
    fun wildcardSubscriptionAvailable() {
        val prop = WildcardSubscriptionAvailable(false)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x28.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertIs<WildcardSubscriptionAvailable>(decoded)
        assertEquals(false, decoded.supported)
    }

    @Test
    fun subscriptionIdentifierAvailable() {
        val prop = SubscriptionIdentifierAvailable(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x29.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertIs<SubscriptionIdentifierAvailable>(decoded)
        assertTrue(decoded.supported)
    }

    @Test
    fun sharedSubscriptionAvailable() {
        val prop = SharedSubscriptionAvailable(false)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x2A.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertIs<SharedSubscriptionAvailable>(decoded)
        assertEquals(false, decoded.supported)
    }

    @Test
    fun maximumQosAtMostOnce() {
        val prop = MaximumQos(false)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x24.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "AT_MOST_ONCE -> false -> 0x00")
        assertIs<MaximumQos>(decoded)
        assertEquals(false, decoded.qos1Allowed)
    }

    @Test
    fun maximumQosAtLeastOnce() {
        val prop = MaximumQos(true)
        assertEquals(2, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x24.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1], "AT_LEAST_ONCE -> true -> 0x01")
        assertIs<MaximumQos>(decoded)
        assertEquals(true, decoded.qos1Allowed)
    }

    // ── Two-byte integer properties (identifier + 2 bytes, size=3) ──────────

    @Test
    fun receiveMaximum() {
        val prop = ReceiveMaximum(100.toUShort())
        assertEquals(3, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x21.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "100 MSB")
        assertEquals(0x64.toByte(), bytes[2], "100 LSB")
        assertIs<ReceiveMaximum>(decoded)
        assertEquals(100.toUShort(), decoded.max)
    }

    @Test
    fun topicAliasMaximum() {
        val prop = TopicAliasMaximum(256.toUShort())
        assertEquals(3, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x23.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1], "256 MSB")
        assertEquals(0x00.toByte(), bytes[2], "256 LSB")
        assertIs<TopicAliasMaximum>(decoded)
        assertEquals(256.toUShort(), decoded.max)
    }

    @Test
    fun topicAlias() {
        val prop = TopicAlias(5.toUShort())
        assertEquals(3, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x22.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x05.toByte(), bytes[2])
        assertIs<TopicAlias>(decoded)
        assertEquals(5.toUShort(), decoded.value)
    }

    @Test
    fun serverKeepAlive() {
        val prop = ServerKeepAlive(3600.toUShort())
        assertEquals(3, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x13.toByte(), bytes[0])
        assertEquals(0x0E.toByte(), bytes[1], "3600 MSB = 0x0E")
        assertEquals(0x10.toByte(), bytes[2], "3600 LSB = 0x10")
        assertIs<ServerKeepAlive>(decoded)
        assertEquals(3600.toUShort(), decoded.seconds)
    }

    // ── Four-byte integer properties (identifier + 4 bytes, size=5) ─────────

    @Test
    fun sessionExpiryInterval() {
        val prop = SessionExpiryInterval(3600u)
        assertEquals(5, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x11.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x0E.toByte(), bytes[3], "3600 >> 8 = 0x0E")
        assertEquals(0x10.toByte(), bytes[4], "3600 & 0xFF = 0x10")
        assertIs<SessionExpiryInterval>(decoded)
        assertEquals(3600u, decoded.seconds)
    }

    @Test
    fun messageExpiryInterval() {
        val prop = MessageExpiryInterval(300u)
        assertEquals(5, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x02.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x01.toByte(), bytes[3])
        assertEquals(0x2C.toByte(), bytes[4], "300 = 0x012C")
        assertIs<MessageExpiryInterval>(decoded)
        assertEquals(300u, decoded.seconds)
    }

    @Test
    fun maximumPacketSize() {
        val prop = MaximumPacketSize(1048576u) // 0x00100000
        assertEquals(5, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x27.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x10.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
        assertEquals(0x00.toByte(), bytes[4])
        assertIs<MaximumPacketSize>(decoded)
        assertEquals(1048576u, decoded.bytes)
    }

    @Test
    fun willDelayInterval() {
        val prop = WillDelayInterval(60u)
        assertEquals(5, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x18.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
        assertEquals(0x3C.toByte(), bytes[4], "60 = 0x3C")
        assertIs<WillDelayInterval>(decoded)
        assertEquals(60u, decoded.seconds)
    }

    // ── UTF-8 string properties (identifier + 2-byte length + UTF-8) ────────

    @Test
    fun contentType() {
        val prop = ContentType("application/json")
        val expectedSize = 1 + 2 + "application/json".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x03.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "length MSB")
        assertEquals(16.toByte(), bytes[2], "length LSB = 16")
        assertIs<ContentType>(decoded)
        assertEquals("application/json", decoded.value)
    }

    @Test
    fun assignedClientIdentifier() {
        val prop = AssignedClientIdentifier("client-123")
        val expectedSize = 1 + 2 + "client-123".length
        assertEquals(expectedSize, propSize(prop))
        val (_, decoded) = roundTrip(prop)
        assertIs<AssignedClientIdentifier>(decoded)
        assertEquals("client-123", decoded.value)
    }

    @Test
    fun authenticationMethod() {
        val prop = AuthenticationMethod("SCRAM-SHA-256")
        val expectedSize = 1 + 2 + "SCRAM-SHA-256".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x15.toByte(), bytes[0])
        assertIs<AuthenticationMethod>(decoded)
        assertEquals("SCRAM-SHA-256", decoded.value)
    }

    @Test
    fun responseInformation() {
        val prop = ResponseInformation("resp-info")
        val expectedSize = 1 + 2 + "resp-info".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x1A.toByte(), bytes[0])
        assertIs<ResponseInformation>(decoded)
        assertEquals("resp-info", decoded.value)
    }

    @Test
    fun serverReference() {
        val prop = ServerReference("mqtt://other.server")
        val expectedSize = 1 + 2 + "mqtt://other.server".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x1C.toByte(), bytes[0])
        assertIs<ServerReference>(decoded)
        assertEquals("mqtt://other.server", decoded.value)
    }

    @Test
    fun reasonString() {
        val prop = ReasonString("something went wrong")
        val expectedSize = 1 + 2 + "something went wrong".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x1F.toByte(), bytes[0])
        assertIs<ReasonString>(decoded)
        assertEquals("something went wrong", decoded.value)
    }

    @Test
    fun responseTopic() {
        val prop = ResponseTopic("response/topic")
        val expectedSize = 1 + 2 + "response/topic".length
        assertEquals(expectedSize, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x08.toByte(), bytes[0])
        assertIs<ResponseTopic>(decoded)
        assertEquals("response/topic", decoded.value)
    }

    // ── UTF-8 string pair property ──────────────────────────────────────────

    @Test
    fun userProperty() {
        val prop = UserProperty("key", "value")
        // 1 (id) + 2 (key len) + 3 (key) + 2 (val len) + 5 (val) = 13
        assertEquals(13, propSize(prop))
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x26.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "key length MSB")
        assertEquals(0x03.toByte(), bytes[2], "key length LSB")
        assertEquals('k'.code.toByte(), bytes[3])
        assertEquals('e'.code.toByte(), bytes[4])
        assertEquals('y'.code.toByte(), bytes[5])
        assertEquals(0x00.toByte(), bytes[6], "value length MSB")
        assertEquals(0x05.toByte(), bytes[7], "value length LSB")
        assertIs<UserProperty>(decoded)
        assertEquals("key", decoded.key)
        assertEquals("value", decoded.value)
    }

    @Test
    fun userPropertyEmpty() {
        val prop = UserProperty("", "")
        assertEquals(5, propSize(prop)) // 1 id + 2 key_len + 0 + 2 val_len + 0
        val (_, decoded) = roundTrip(prop)
        assertIs<UserProperty>(decoded)
        assertEquals("", decoded.key)
        assertEquals("", decoded.value)
    }

    // ── Binary data properties ──────────────────────────────────────────────

    @Test
    fun correlationData() {
        val data = BufferFactory.Default.allocate(4)
        data.writeByte(0xCA.toByte())
        data.writeByte(0xFE.toByte())
        data.writeByte(0xBA.toByte())
        data.writeByte(0xBE.toByte())
        data.resetForRead()
        val prop = CorrelationData(4.toUShort(), data)
        assertEquals(7, propSize(prop)) // 1 id + 2 len + 4 data
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x09.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1], "data length MSB")
        assertEquals(0x04.toByte(), bytes[2], "data length LSB")
        assertEquals(0xCA.toByte(), bytes[3])
        assertEquals(0xFE.toByte(), bytes[4])
        assertEquals(0xBA.toByte(), bytes[5])
        assertEquals(0xBE.toByte(), bytes[6])
        assertIs<CorrelationData<*>>(decoded)
        val decodedData = decoded.data as ReadBuffer
        decodedData.position(0)
        assertEquals(4, decodedData.remaining())
    }

    @Test
    fun correlationDataEmpty() {
        val data = ReadBuffer.EMPTY_BUFFER
        val prop = CorrelationData(0.toUShort(), data)
        assertEquals(3, propSize(prop)) // 1 id + 2 len + 0
        val (bytes, _) = roundTrip(prop)
        assertEquals(0x09.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
    }

    @Test
    fun authenticationData() {
        val data = BufferFactory.Default.allocate(3)
        data.writeByte(0x01)
        data.writeByte(0x02)
        data.writeByte(0x03)
        data.resetForRead()
        val prop = AuthenticationData(3.toUShort(), data)
        assertEquals(6, propSize(prop)) // 1 id + 2 len + 3 data
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x16.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
        assertEquals(0x01.toByte(), bytes[3])
        assertEquals(0x02.toByte(), bytes[4])
        assertEquals(0x03.toByte(), bytes[5])
        assertIs<AuthenticationData<*>>(decoded)
        val decodedData = decoded.data as ReadBuffer
        decodedData.position(0)
        assertEquals(3, decodedData.remaining())
    }

    // ── Variable byte integer property ──────────────────────────────────────

    @Test
    fun subscriptionIdentifierSmall() {
        val prop = SubscriptionIdentifier(1)
        assertEquals(2, propSize(prop)) // 1 id + 1 byte VBI
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x0B.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1], "VBI for 1 = single byte")
        assertIs<SubscriptionIdentifier>(decoded)
        assertEquals(1, decoded.value)
    }

    @Test
    fun subscriptionIdentifierTwoByte() {
        val prop = SubscriptionIdentifier(128)
        assertEquals(3, propSize(prop)) // 1 id + 2 byte VBI
        val (bytes, decoded) = roundTrip(prop)
        assertEquals(0x0B.toByte(), bytes[0])
        assertEquals(0x80.toByte(), bytes[1], "VBI 128: first byte = 0x80 (continuation)")
        assertEquals(0x01.toByte(), bytes[2], "VBI 128: second byte = 0x01")
        assertIs<SubscriptionIdentifier>(decoded)
        assertEquals(128, decoded.value)
    }

    @Test
    fun subscriptionIdentifierMax() {
        // Max VBI: 268,435,455 (0x0FFFFFFF) = 4 bytes
        val prop = SubscriptionIdentifier(268_435_455)
        assertEquals(5, propSize(prop)) // 1 id + 4 byte VBI
        val (_, decoded) = roundTrip(prop)
        assertIs<SubscriptionIdentifier>(decoded)
        assertEquals(268_435_455, decoded.value)
    }

    // ── Size regression tests ───────────────────────────────────────────────

    @Test
    fun allBooleanPropertiesSize2() {
        assertEquals(2, propSize(PayloadFormatIndicator(false)))
        assertEquals(2, propSize(RequestProblemInformation(false)))
        assertEquals(2, propSize(RequestResponseInformation(false)))
        assertEquals(2, propSize(RetainAvailable(false)))
        assertEquals(2, propSize(WildcardSubscriptionAvailable(false)))
        assertEquals(2, propSize(SubscriptionIdentifierAvailable(false)))
        assertEquals(2, propSize(SharedSubscriptionAvailable(false)))
        assertEquals(2, propSize(MaximumQos(false)))
    }

    @Test
    fun allTwoByteIntPropertiesSize3() {
        assertEquals(3, propSize(ReceiveMaximum(0.toUShort())))
        assertEquals(3, propSize(TopicAliasMaximum(0.toUShort())))
        assertEquals(3, propSize(TopicAlias(0.toUShort())))
        assertEquals(3, propSize(ServerKeepAlive(0.toUShort())))
    }

    @Test
    fun allFourByteIntPropertiesSize5() {
        assertEquals(5, propSize(SessionExpiryInterval(0u)))
        assertEquals(5, propSize(MessageExpiryInterval(0u)))
        assertEquals(5, propSize(MaximumPacketSize(1u)))
        assertEquals(5, propSize(WillDelayInterval(0u)))
    }

    @Test
    fun sizeMatchesActualBytesWrittenForAllTypes() {
        val binaryData = BufferFactory.Default.allocate(2)
        binaryData.writeByte(0x01)
        binaryData.writeByte(0x02)
        binaryData.resetForRead()
        val binaryData2 = BufferFactory.Default.allocate(2)
        binaryData2.writeByte(0x03)
        binaryData2.writeByte(0x04)
        binaryData2.resetForRead()
        val properties =
            listOf<MqttProperty>(
                PayloadFormatIndicator(true),
                MessageExpiryInterval(300u),
                ContentType("text/plain"),
                ResponseTopic("t"),
                CorrelationData(2.toUShort(), binaryData),
                SessionExpiryInterval(3600u),
                ReceiveMaximum(100.toUShort()),
                MaximumPacketSize(65536u),
                TopicAliasMaximum(10.toUShort()),
                RequestResponseInformation(true),
                RequestProblemInformation(false),
                UserProperty("a", "b"),
                AuthenticationMethod("plain"),
                AuthenticationData(2.toUShort(), binaryData2),
                ReasonString("ok"),
                ServerKeepAlive(60.toUShort()),
                ResponseInformation("info"),
                ServerReference("server"),
                AssignedClientIdentifier("cid"),
                TopicAlias(1.toUShort()),
                MaximumQos(true),
                RetainAvailable(true),
                WildcardSubscriptionAvailable(true),
                SubscriptionIdentifierAvailable(true),
                SharedSubscriptionAvailable(true),
                SubscriptionIdentifier(42),
                WillDelayInterval(120u),
            )
        for (prop in properties) {
            val size = propSize(prop)
            val buf = BufferFactory.Default.allocate(size + 1)
            encodeProperty(buf, prop)
            buf.resetForRead()
            val written = buf.remaining()
            assertEquals(
                size,
                written,
                "size mismatch for ${prop::class.simpleName}: propSize=$size but written=$written",
            )
        }
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    /** Size of property on the wire: encode to a temp buffer and measure bytes written */
    private fun propSize(prop: MqttProperty): Int {
        val buf = BufferFactory.Default.allocate(128)
        encodeProperty(buf, prop)
        buf.resetForRead()
        return buf.remaining()
    }

    /**
     * Writes a property to a buffer via codec, reads back the raw bytes and decodes
     * via MqttPropertyCodec.decode(). Returns both the raw bytes and the decoded property.
     */
    private fun roundTrip(prop: MqttProperty): Pair<ByteArray, MqttProperty> {
        val size = propSize(prop)
        val buffer = BufferFactory.Default.allocate(size + 1)
        encodeProperty(buffer, prop)
        buffer.resetForRead()
        val rawBytes = ByteArray(buffer.remaining())
        for (i in rawBytes.indices) {
            rawBytes[i] = buffer.readByte()
        }
        buffer.resetForRead()
        val decoded =
            MqttPropertyCodec.decode<ReadBuffer, ReadBuffer>(
                buffer,
                decodeAuthenticationDataData = { slice -> slice },
                decodeCorrelationDataData = { slice -> slice },
            )
        return Pair(rawBytes, decoded)
    }
}
