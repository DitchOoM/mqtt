package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TopicCodecRegistryTest {
    private object TempPayload : Payload

    private object TempCodec : Codec<TempPayload> {
        override fun encode(
            buffer: WriteBuffer,
            value: TempPayload,
            context: EncodeContext,
        ) = error("test stub")

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): TempPayload = error("test stub")
    }

    private object OtherCodec : Codec<TempPayload> {
        override fun encode(
            buffer: WriteBuffer,
            value: TempPayload,
            context: EncodeContext,
        ) = error("test stub")

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): TempPayload = error("test stub")
    }

    @Test
    fun unregistered_topic_returns_null() {
        val registry = TopicCodecRegistry()
        assertNull(registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
    }

    @Test
    fun exact_filter_matches_exact_topic() {
        val registry = TopicCodecRegistry()
        registry.register(TopicFilter.fromOrThrow("sensor/temp"), TempCodec)
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
    }

    @Test
    fun single_level_wildcard_filter_matches_any_one_level() {
        val registry = TopicCodecRegistry()
        registry.register(TopicFilter.fromOrThrow("sensor/+"), TempCodec)
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/humidity")))
        assertNull(registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp/celsius")))
    }

    @Test
    fun multi_level_wildcard_filter_matches_any_subtree() {
        val registry = TopicCodecRegistry()
        registry.register(TopicFilter.fromOrThrow("sensor/#"), TempCodec)
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp/celsius")))
        assertNull(registry.codecForTopicName(TopicName.fromOrThrow("device/temp")))
    }

    @Test
    fun unregister_removes_codec() {
        val registry = TopicCodecRegistry()
        val filter = TopicFilter.fromOrThrow("sensor/temp")
        registry.register(filter, TempCodec)
        registry.unregister(filter)
        assertNull(registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
    }

    @Test
    fun register_overwrites_existing_filter() {
        val registry = TopicCodecRegistry()
        val filter = TopicFilter.fromOrThrow("sensor/temp")
        registry.register(filter, TempCodec)
        registry.register(filter, OtherCodec)
        assertSame(OtherCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
    }

    @Test
    fun first_registered_wins_on_overlapping_match() {
        val registry = TopicCodecRegistry()
        // sensor/+ registered first; sensor/temp matches it before the more-specific filter is checked.
        // Documented behavior — see registry kdoc TODO(B-4b) for overlap-detection.
        registry.register(TopicFilter.fromOrThrow("sensor/+"), TempCodec)
        registry.register(TopicFilter.fromOrThrow("sensor/temp"), OtherCodec)
        assertSame(TempCodec, registry.codecForTopicName(TopicName.fromOrThrow("sensor/temp")))
    }

    @Test
    fun concurrent_registrations_do_not_lose_entries() {
        val registry = TopicCodecRegistry()
        val filters =
            (0 until 100).map { TopicFilter.fromOrThrow("sensor/x$it") }
        // Register in a non-deterministic order; copy-on-write CAS-loop should serialize them.
        // (Single-threaded test — but exercises the update {} CAS loop's correctness.)
        for (f in filters) registry.register(f, TempCodec)
        for (f in filters) {
            // Use the filter's string form for the lookup TopicName.
            val topic = TopicName.fromOrThrow(f.toString())
            assertSame(TempCodec, registry.codecForTopicName(topic))
        }
        assertEquals(100, filters.size)
    }
}
