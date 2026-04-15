package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishDispatcherTest {
    private fun makePayload(vararg bytes: Byte): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return buf
    }

    private fun v4Publish(
        topic: String,
        payload: ReadBuffer,
        qos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        packetId: Int = 1,
    ): PublishMessage =
        PublishMessageV4.ofRaw(
            topic = TopicName.fromOrThrow(topic),
            qos = qos,
            payload = payload,
            packetIdentifier =
                if (qos == QualityOfService.AT_MOST_ONCE) {
                    com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
                } else {
                    packetId
                },
        )

    // ── Untyped subscribe ──────────────────────────────────────────────

    @Test
    fun dispatchInvokesMatchingUntypedHandler() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<PublishMessage>()
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
    fun dispatchReturnsFalseWhenNoMatch() =
        runTest {
            val dispatcher = PublishDispatcher()
            dispatcher.subscribe(
                TopicFilter.fromOrThrow("sensor/+"),
                SubscriptionHandler.Async { },
            )
            val matched = dispatcher.dispatch(v4Publish("other/topic", makePayload()))
            assertFalse(matched)
        }

    @Test
    fun dispatchInvokesBlockingHandler() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<PublishMessage>()
            dispatcher.subscribe(
                TopicFilter.fromOrThrow("alerts/#"),
                SubscriptionHandler.Blocking { publish -> received.add(publish) },
            )
            dispatcher.dispatch(v4Publish("alerts/fire", makePayload(7)))
            assertEquals(1, received.size)
        }

    // ── Typed subscribe ───────────────────────────────────────────────

    private object IntPayloadCodec : PayloadCodec<Int> {
        override fun decode(buffer: ReadBuffer): Int = buffer.readInt()

        override fun encode(
            buffer: com.ditchoom.buffer.WriteBuffer,
            value: Int,
        ) {
            buffer.writeInt(value)
        }

        override fun encodedSize(value: Int): Int = Int.SIZE_BYTES
    }

    private data class StringBytesCodec(
        val charset: com.ditchoom.buffer.Charset = com.ditchoom.buffer.Charset.UTF8,
    ) : PayloadCodec<String> {
        override fun decode(buffer: ReadBuffer): String = buffer.readString(buffer.remaining(), charset)

        override fun encode(
            buffer: com.ditchoom.buffer.WriteBuffer,
            value: String,
        ) {
            buffer.writeString(value, charset)
        }

        override fun encodedSize(value: String): Int = value.encodeToByteArray().size
    }

    @Test
    fun typedSubscribeDecodesPayloadThroughCodec() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<Pair<PublishMessage, Int>>()
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("data/#"),
                SubscriberEntry.Typed(IntPayloadCodec) { pub, decoded ->
                    received.add(pub to decoded)
                },
            )

            dispatcher.dispatch(v4Publish("data/values", makePayload(0, 0, 0, 42)))
            assertEquals(1, received.size)
            assertEquals(42, received[0].second)
            assertEquals(TopicName.fromOrThrow("data/values"), received[0].first.topic)
        }

    @Test
    fun typedSubscribeHandlerReceivesMessageAndDecodedPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<Pair<PublishMessage, Int>>()
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("cmd/+"),
                SubscriberEntry.Typed(IntPayloadCodec) { pub, decoded ->
                    received.add(pub to decoded)
                },
            )
            dispatcher.dispatch(v4Publish("cmd/run", makePayload(0, 0, 1, 0)))

            assertEquals(1, received.size)
            assertEquals(256, received[0].second)
            assertEquals(TopicName.fromOrThrow("cmd/run"), received[0].first.topic)
        }

    // ── Multi-subscriber ──────────────────────────────────────────────

    @Test
    fun multipleSubscribersSameTopicEachGetTypedPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val intResults = mutableListOf<Int>()
            val strResults = mutableListOf<String>()
            // Two subscribers on overlapping filters, each with its own codec.
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("dual/int"),
                SubscriberEntry.Typed(IntPayloadCodec) { _, decoded -> intResults.add(decoded) },
            )
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("dual/+"),
                SubscriberEntry.Typed(StringBytesCodec()) { _, decoded -> strResults.add(decoded) },
            )

            // Only the int subscriber's exact filter matches; the second filter matches too.
            // Both run codec decode from their own copy of the payload.
            val payload = BufferFactory.Default.allocate(4)
            payload.writeByte(0)
            payload.writeByte(0)
            payload.writeByte(0)
            payload.writeByte(9)
            payload.resetForRead()
            val matched = dispatcher.dispatch(v4Publish("dual/int", payload))

            assertTrue(matched)
            assertEquals(listOf(9), intResults)
            assertEquals(1, strResults.size)
        }

    // ── Empty-payload typed decode ────────────────────────────────────

    @Test
    fun typedSubscribeReceivesEmptyPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<String>()
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("empty/+"),
                SubscriberEntry.Typed(StringBytesCodec()) { _, s -> received.add(s) },
            )
            dispatcher.dispatch(v4Publish("empty/x", BufferFactory.Default.allocate(0).also { it.resetForRead() }))
            assertEquals(listOf(""), received)
        }

    // ── Unsubscribe / clear ───────────────────────────────────────────

    @Test
    fun unsubscribeRemovesHandler() =
        runTest {
            val dispatcher = PublishDispatcher()
            val filter = TopicFilter.fromOrThrow("remove/me")
            val received = mutableListOf<PublishMessage>()
            dispatcher.subscribe(filter, SubscriptionHandler.Async { received.add(it) })
            dispatcher.dispatch(v4Publish("remove/me", makePayload(1)))
            assertEquals(1, received.size)

            dispatcher.unsubscribe(filter)
            val matched = dispatcher.dispatch(v4Publish("remove/me", makePayload(2)))
            assertFalse(matched)
            assertEquals(1, received.size)
        }

    @Test
    fun clearRemovesAllHandlers() =
        runTest {
            val dispatcher = PublishDispatcher()
            dispatcher.subscribe(TopicFilter.fromOrThrow("a/#"), SubscriptionHandler.Async { })
            dispatcher.subscribeTyped(
                TopicFilter.fromOrThrow("b/+"),
                SubscriberEntry.Typed(IntPayloadCodec) { _, _ -> },
            )
            assertFalse(dispatcher.isEmpty())
            dispatcher.clear()
            assertTrue(dispatcher.isEmpty())
        }
}
