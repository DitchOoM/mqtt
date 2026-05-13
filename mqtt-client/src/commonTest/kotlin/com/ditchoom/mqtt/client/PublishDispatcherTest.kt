package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
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
    //
    // The MqttCodec layer attaches the typed payload to `PublishMessageV4<P>.payload`
    // (Pattern #1/#2). PublishDispatcher only routes — the typed value flows verbatim.

    @Test
    fun typedSubscribeReceivesAlreadyTypedPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<Pair<PublishMessage, OpaquePublishPayload>>()
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("data/#"),
                SubscriberEntry.Typed { pub, decoded -> received.add(pub to decoded) },
            )

            dispatcher.dispatch(v4Publish("data/values", makePayload(0, 0, 0, 42)))
            assertEquals(1, received.size)
            assertEquals(4, received[0].second.byteSize())
            assertEquals(TopicName.fromOrThrow("data/values"), received[0].first.topic)
        }

    @Test
    fun typedSubscribeHandlerReceivesMessageAndPayloadInstance() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<Pair<PublishMessage, OpaquePublishPayload>>()
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("cmd/+"),
                SubscriberEntry.Typed { pub, decoded -> received.add(pub to decoded) },
            )
            dispatcher.dispatch(v4Publish("cmd/run", makePayload(0, 0, 1, 0)))

            assertEquals(1, received.size)
            assertEquals(4, received[0].second.byteSize())
            assertEquals(TopicName.fromOrThrow("cmd/run"), received[0].first.topic)
        }

    // ── Multi-subscriber: one codec per topic — both handlers see the same typed value ──

    @Test
    fun multipleSubscribersSameTopicEachGetTypedPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val results = mutableListOf<OpaquePublishPayload>()
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("dual/int"),
                SubscriberEntry.Typed { _, decoded -> results.add(decoded) },
            )
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("dual/+"),
                SubscriberEntry.Typed { _, decoded -> results.add(decoded) },
            )

            val matched = dispatcher.dispatch(v4Publish("dual/int", makePayload(0, 0, 0, 9)))

            assertTrue(matched)
            assertEquals(2, results.size, "both filters match the same topic — both handlers fire")
        }

    // ── Empty-payload typed decode ────────────────────────────────────

    @Test
    fun typedSubscribeReceivesEmptyPayload() =
        runTest {
            val dispatcher = PublishDispatcher()
            val received = mutableListOf<OpaquePublishPayload>()
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("empty/+"),
                SubscriberEntry.Typed { _, p -> received.add(p) },
            )
            dispatcher.dispatch(v4Publish("empty/x", BufferFactory.Default.allocate(0).also { it.resetForRead() }))
            assertEquals(1, received.size)
            assertEquals(0, received.single().byteSize())
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
            dispatcher.subscribeTyped<OpaquePublishPayload>(
                TopicFilter.fromOrThrow("b/+"),
                SubscriberEntry.Typed { _, _ -> },
            )
            assertFalse(dispatcher.isEmpty())
            dispatcher.clear()
            assertTrue(dispatcher.isEmpty())
        }
}
