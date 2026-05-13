package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Post-buffer-v1 invariants. Each test pins behaviour of the typed-payload PUBLISH path
 * that replaced the old `rawPayload()` / decoder-lambda model.
 */
class PublishContractTests {
    // ── ControlPacket.serialize(factory) returns a read-positioned buffer ──

    @Test
    fun controlPacketSerializeReturnsReadPositionedBuffer() {
        // PingRequest is 2 bytes on the wire (0xC0 0x00).
        val serialized: ReadBuffer = (PingRequest() as ControlPacket).serialize()
        assertEquals(2, serialized.remaining(), "returned buffer should be read-ready")
        assertEquals(0xC0u, serialized.readUnsignedByte())
        assertEquals(0x00u, serialized.readUnsignedByte())
    }

    // ── Typed dispatch — every matching subscriber sees the same already-typed payload ──

    @Test
    fun typedDispatchDeliversTypedPayloadToEverySubscriber() =
        runTest {
            val dispatcher = PublishDispatcher()
            val seenByA = mutableListOf<OpaquePublishPayload>()
            val seenByB = mutableListOf<OpaquePublishPayload>()
            dispatcher.subscribe<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("dual/one"),
                SubscriberEntry { _, payload -> seenByA.add(payload) },
            )
            dispatcher.subscribe<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("dual/+"),
                SubscriberEntry { _, payload -> seenByB.add(payload) },
            )

            val publish = rawPublish("dual/one", payload(1, 2, 3, 4))
            dispatcher.dispatch(publish)

            assertEquals(1, seenByA.size)
            assertEquals(1, seenByB.size)
            assertSame(
                seenByA.single(),
                seenByB.single(),
                "Every matching subscriber receives the same typed payload — no per-subscriber decode",
            )
        }

    // ── Empty-payload PUBLISH still delivers a typed value (zero-byte handle) ──

    @Test
    fun emptyPayloadDispatchDeliversZeroByteHandle() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<OpaquePublishPayload>()
            dispatcher.subscribe<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("empty/+"),
                SubscriberEntry { _, payload -> received.add(payload) },
            )

            dispatcher.dispatch(
                rawPublish("empty/x", BufferFactory.Default.allocate(0).also { it.resetForRead() }),
            )

            assertEquals(1, received.size)
            assertEquals(0, received.single().byteSize(), "empty payload must deliver a zero-byte handle")
        }

    // ── Wire bytes round-trip through OpaquePublishPayload ──

    @Test
    fun opaquePayloadRoundTripsBytes() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val pub = rawPublish("topic", BufferFactory.Default.wrap(bytes))

        @Suppress("UNCHECKED_CAST")
        val handle = (pub as PublishMessageV4<OpaquePublishPayload>).payload.handle
        assertEquals(bytes.size, handle.byteSize())
        val view = handle.asReadBuffer()
        for (i in bytes.indices) {
            assertTrue(view.readUnsignedByte().toByte() == bytes[i])
        }
    }

    // ── Fixtures ──

    private fun payload(vararg bytes: Byte): ReadBuffer =
        BufferFactory.Default.allocate(bytes.size).apply {
            bytes.forEach { writeByte(it) }
            resetForRead()
        }

    private fun rawPublish(
        topic: String,
        payload: ReadBuffer,
    ): PublishMessage =
        PublishMessageV4.ofRaw(
            topic = TopicName.fromOrThrow(topic),
            qos = QualityOfService.AT_MOST_ONCE,
            payload = payload,
        )
}
