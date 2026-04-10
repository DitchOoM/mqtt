package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.mqtt.controlpacket.IncomingPublish
import com.ditchoom.mqtt.controlpacket.IncomingPublishV5
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessage as V4PublishMessage
import com.ditchoom.mqtt5.controlpacket.PublishMessage as V5PublishMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublishDispatcherTest {

    private fun makePayload(vararg bytes: Byte): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return buf
    }

    private fun v4Publish(topic: String, payload: ReadBuffer?) =
        V4PublishMessage(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 1,
            payload = payload,
        ).toIncomingPublish()

    // ── Basic dispatch ──────────────────────────────────────────────────

    @Test
    fun dispatchInvokesMatchingHandler() = runTest {
        val dispatcher = PublishDispatcher()
        val received = mutableListOf<IncomingPublish<ReadBuffer?>>()
        dispatcher.subscribe(
            TopicFilter.fromOrThrow("sensor/+"),
            SubscriptionHandler.Async { publish -> received.add(publish) },
        )

        val matched = dispatcher.dispatch(v4Publish("sensor/temp", makePayload(42)))
        assertTrue(matched)
        assertEquals(1, received.size)
        assertEquals(TopicName.fromOrThrow("sensor/temp"), received[0].topic)
    }

    @Test
    fun dispatchReturnsFalseWhenNoMatch() = runTest {
        val dispatcher = PublishDispatcher()
        dispatcher.subscribe(
            TopicFilter.fromOrThrow("sensor/+"),
            SubscriptionHandler.Async { },
        )
        val matched = dispatcher.dispatch(v4Publish("other/topic", null))
        assertFalse(matched)
    }

    // ── Typed subscription emits IncomingPublish<P> ─────────────────────

    @Test
    fun subscribeTypedEmitsIncomingPublishWithDecodedPayload() = runTest {
        val dispatcher = PublishDispatcher()
        val intDecoder = PayloadDecoder<Int> { readInt() }
        val flow = dispatcher.subscribeTyped(
            TopicFilter.fromOrThrow("data/#"),
            intDecoder,
        )

        val payload = makePayload(0, 0, 0, 42) // Int 42 big-endian
        val raw = v4Publish("data/values", payload)

        // Start collecting before dispatch so we don't miss the emission
        val result = kotlinx.coroutines.CompletableDeferred<IncomingPublish<Int>>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            result.complete(flow.first())
        }

        dispatcher.dispatch(raw)
        val incoming = result.await()

        assertEquals(42, incoming.payload)
        assertEquals(TopicName.fromOrThrow("data/values"), incoming.topic)
        assertEquals(QualityOfService.AT_LEAST_ONCE, incoming.qos)

        job.cancel()
    }

    @Test
    fun subscribeTypedHandlerReceivesIncomingPublish() = runTest {
        val dispatcher = PublishDispatcher()
        val intDecoder = PayloadDecoder<Int> { readInt() }
        val received = mutableListOf<IncomingPublish<Int>>()
        dispatcher.subscribeTyped(
            TopicFilter.fromOrThrow("cmd/+"),
            intDecoder,
            handler = { publish -> received.add(publish) },
        )

        val payload = makePayload(0, 0, 1, 0) // Int 256
        dispatcher.dispatch(v4Publish("cmd/run", payload))

        assertEquals(1, received.size)
        assertEquals(256, received[0].payload)
        assertEquals(TopicName.fromOrThrow("cmd/run"), received[0].topic)
    }

    @Test
    fun subscribeTypedPreservesV5Properties() = runTest {
        val dispatcher = PublishDispatcher()
        val intDecoder = PayloadDecoder<Int> { readInt() }
        val received = mutableListOf<IncomingPublish<Int>>()
        dispatcher.subscribeTyped(
            TopicFilter.fromOrThrow("v5/#"),
            intDecoder,
            handler = { received.add(it) },
        )

        val wire = V5PublishMessage(
            topicName = "v5/test",
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 5,
            contentType = "application/octet-stream",
            responseTopicName = "reply/v5",
            payload = makePayload(0, 0, 0, 99),
        )
        dispatcher.dispatch(wire.toIncomingPublish())

        assertEquals(1, received.size)
        val msg = received[0]
        assertIs<IncomingPublishV5<Int>>(msg)
        assertEquals(99, msg.payload)
        assertEquals("application/octet-stream", msg.contentType)
        assertEquals(TopicName.fromOrThrow("reply/v5"), msg.responseTopic)
    }
}
