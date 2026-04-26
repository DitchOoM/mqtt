package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.payloadAsByteArrayOrNull
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Post-Phase-4 invariants. Each test pins a contract surfaced by the 5 findings so the
 * behaviour can't silently regress.
 */
class PublishContractTests {
    // ── Finding 1: rawPayload exposes a stable wire slice ──

    @Test
    fun rawPayloadIsStableAcrossReads() {
        val source = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))
        val pub = rawPublish("topic", source)
        val raw = pub.rawPayload()
        assertEquals(4, raw?.remaining(), "rawPayload exposes the full wire bytes")
        // Slicing yields an independent reader that doesn't perturb the underlying buffer.
        val sliceA = raw!!.slice()
        val sliceB = raw.slice()
        sliceA.readUnsignedByte()
        assertEquals(3, sliceA.remaining())
        assertEquals(4, sliceB.remaining(), "independent slices don't share position")
    }

    // ── Finding 2: ControlPacket.serialize(factory) returns read-positioned buffer ──

    @Test
    fun controlPacketSerializeReturnsReadPositionedBuffer() {
        // PingRequest is 2 bytes on the wire (0xC0 0x00). Serialize and assert the returned
        // buffer is ready to read without the caller resetting it.
        val serialized: ReadBuffer = (PingRequest as ControlPacket).serialize()
        assertEquals(2, serialized.remaining(), "returned buffer should be read-ready")
        assertEquals(0xC0u, serialized.readUnsignedByte())
        assertEquals(0x00u, serialized.readUnsignedByte())
    }

    // ── Finding 4: zero-copy dispatch — each typed subscriber gets an independent slice ──

    /** Records each `ReadBuffer` slice handed to it for cross-subscriber identity checks. */
    private class RecordingDecoder {
        val decodeInputs = mutableListOf<ReadBuffer>()

        @Suppress("NoByteArrayInProd") // test fixture: assertion on payload contents
        val lambda: ReadBuffer.() -> ByteArray = {
            decodeInputs.add(this)
            readByteArray(remaining())
        }
    }

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

    @Test
    fun typedDispatchGivesEachSubscriberIndependentSlice() =
        runTest {
            val dispatcher = PublishDispatcher()
            val recorderA = RecordingDecoder()
            val recorderB = RecordingDecoder()
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("dual/one"),
                SubscriberEntry.Typed(recorderA.lambda) { _, _ -> },
            )
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("dual/+"),
                SubscriberEntry.Typed(recorderB.lambda) { _, _ -> },
            )

            dispatcher.dispatch(rawPublish("dual/one", payload(1, 2, 3, 4)))

            assertEquals(1, recorderA.decodeInputs.size)
            assertEquals(1, recorderB.decodeInputs.size)
            // Each subscriber receives its own slice — not the same object.
            assertNotSame(
                recorderA.decodeInputs[0],
                recorderB.decodeInputs[0],
                "each typed subscriber must receive an independent slice",
            )
        }

    // ── Finding 5: empty-payload PUBLISH delivered to typed lambda without allocation ──

    @Test
    fun emptyPayloadDispatchUsesSharedEmptyBuffer() =
        runTest {
            val dispatcher = PublishDispatcher()
            val recorder = RecordingDecoder()
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("empty/+"),
                SubscriberEntry.Typed(recorder.lambda) { _, _ -> },
            )

            dispatcher.dispatch(
                rawPublish("empty/x", BufferFactory.Default.allocate(0).also { it.resetForRead() }),
            )

            assertEquals(1, recorder.decodeInputs.size)
            val delivered = recorder.decodeInputs[0]
            assertEquals(0, delivered.remaining(), "empty payload must deliver a zero-remaining buffer")
            // Verify it's the shared singleton — no per-dispatch allocation for empty payloads.
            assertEquals(ReadBuffer.EMPTY_BUFFER, delivered)
        }

    // ── API split: payloadAsByteArrayOrNull copies bytes from the wire payload ──

    @Test
    fun payloadAsByteArrayOrNullCopiesBytes() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val pub = rawPublish("topic", BufferFactory.Default.wrap(bytes))
        val result = pub.payloadAsByteArrayOrNull()
        assertEquals(4, result?.size)
        assertTrue(result!!.contentEquals(bytes), "bytes must round-trip through payloadAsByteArrayOrNull")
    }

    @Test
    fun payloadAsByteArrayOrNullReturnsNullForEmptyPayload() {
        val pub = rawPublish("topic", BufferFactory.Default.allocate(0).also { it.resetForRead() })
        assertNull(pub.payloadAsByteArrayOrNull(), "zero-length payload returns null (no BLOB to store)")
    }

    // ── API split: rawPayload returns shared reference for wire-decoded publishes ──

    @Test
    fun rawPayloadReturnsSharedPayloadBuffer() {
        val buf = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))
        val pub = rawPublish("topic", buf)
        // rawPayload returns the stored ReadBuffer directly (zero-copy), same identity.
        val raw = pub.rawPayload()
        assertEquals(4, raw?.remaining())
        // Contract: reading from the returned buffer advances its position —
        // callers that need independent reads must slice() first.
        raw?.readUnsignedByte()
        assertEquals(3, raw?.remaining())
    }
}
