package com.ditchoom.mqtt5.controlpacket.fuzz

import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.mqtt5.controlpacket.PublishProperties
import com.ditchoom.mqtt5.controlpacket.decodeV5
import com.ditchoom.mqtt5.controlpacket.encodeToReadBufferV5
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KNOWN UPSTREAM BUG — found by [ControlPacketV5FuzzTest.roundTripRandomValidPackets]
 * (seed 7886212465788453954, iteration 1352).
 *
 * The buffer-codec KSP processor (com.ditchoom:buffer-codec-processor 6.3.0) emits a fixed
 * `BufferFactory.Default.allocate(64, ...)` scratch buffer for every
 * `@LengthPrefixed @UseCodec(MqttRemainingLengthCodec) List<MqttProperty>` section (see the
 * generated ControlPacketV5PublishCodec.encode). When the encoded property list exceeds
 * 64 bytes — two user properties suffice — the JVM `WriteBuffer.writeString` silently stops
 * at the scratch buffer's limit instead of growing or throwing, and the truncated byte
 * count is what gets length-prefixed: the encoder emits a well-formed wire frame carrying
 * SILENTLY CORRUPTED (truncated) properties. Every v5 packet type with a property list is
 * affected (CONNECT, CONNACK, PUBLISH, all ACKs, SUBSCRIBE, DISCONNECT, AUTH).
 *
 * Fix belongs in the buffer repo — tracked as DitchOoM/buffer#249 (processor scratch) and
 * DitchOoM/buffer#250 (JVM writeString overflow). Remove the @Ignore once mqtt picks up a
 * buffer release containing the fix. Until then the fuzz generator (V5PacketGenerator)
 * deliberately keeps generated property lists under 64 encoded bytes.
 */
class UpstreamCodecBugRegressionTest {
    @Ignore // DitchOoM/buffer#249 + #250: 64-byte property scratch truncates silently
    @Test
    fun propertyListOver64BytesRoundTrips() {
        val packet =
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow("3ELi"),
                payload = bytesToReadBuffer("Pvf32JhdvP4mx".encodeToByteArray()),
                properties =
                    PublishProperties(
                        messageExpiryInterval = 12975,
                        topicAlias = 33619,
                        userProperty = listOf("60aVhyK" to "Fcx230pRgZ", "BflWUG5K" to "T5DuuQpO"),
                        // 68 encoded property bytes total; the fixed scratch truncates to 64,
                        // eating the tail of this content type.
                        contentType = "XM9KVWah3kbCfL",
                    ),
            )
        val encoded = encodeToReadBufferV5(packet)
        val bytes = encoded.readByteArray(encoded.remaining())
        val decoded = decodeV5(bytesToReadBuffer(bytes)) as ControlPacketV5.Publish<*>
        assertEquals(packet.properties, decoded.properties)
    }
}
