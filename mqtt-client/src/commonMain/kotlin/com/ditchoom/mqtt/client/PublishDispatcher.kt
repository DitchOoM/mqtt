package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5

/**
 * Dispatches incoming publish messages to registered [SubscriberEntry] instances
 * using a [TopicTrie] for O(segments) wildcard matching.
 *
 * Under the zero-copy decode model (the topic-router codec at the MqttCodec
 * layer picks the codec from [TopicCodecRegistry] while the wire frame is
 * still alive), incoming `PublishMessage` instances already carry their
 * typed payload via the concrete subtype's `payload: P` field. Dispatch is
 * pure routing — no re-decode from raw bytes, no per-subscriber decode
 * lambda. The typed payload is extracted from the concrete subtype here and
 * handed to each matching [SubscriberEntry].
 *
 * "One codec per topic" semantics: all subscribers matching an incoming
 * topic receive the same typed payload. If two subscribers register different
 * codec result types for overlapping filters, the type cast in
 * [SubscriberEntry] throws `ClassCastException`. Users who need
 * multi-codec semantics for the same topic should register
 * `OpaquePublishPayloadCodec` and re-decode in their handler.
 */
internal class PublishDispatcher {
    private val trie = TopicTrie<SubscriberEntry<*>>()

    /** Register a typed subscriber. The codec lives in [TopicCodecRegistry]; this entry only routes. */
    fun <P> subscribe(
        filter: TopicFilter,
        entry: SubscriberEntry<P>,
    ): SubscriberEntry<*>? = trie.insert(filter, entry)

    /** Remove the handler for the given topic filter. */
    fun unsubscribe(filter: TopicFilter): SubscriberEntry<*>? = trie.remove(filter)

    /**
     * Dispatch an incoming publish to all matching subscribers.
     *
     * @return true if at least one handler was invoked
     */
    suspend fun dispatch(publish: PublishMessage): Boolean {
        val entries = trie.matchAll(publish.topic)
        if (entries.isEmpty()) return false
        val typedPayload = typedPayloadOf(publish) ?: return false
        for (entry in entries) {
            entry.dispatch(publish, typedPayload)
        }
        return true
    }

    /** Returns true if any handler would match the given [PublishMessage]. */
    fun hasMatch(publish: PublishMessage): Boolean = trie.hasMatch(publish.topic)

    /** Returns true if no handlers are registered. */
    fun isEmpty(): Boolean = trie.isEmpty()

    /** Remove all handlers. */
    fun clear() = trie.clear()

    /**
     * Extract the typed `payload` field from the concrete v4 / v5 PUBLISH
     * variant. Returns null for any other [PublishMessage] subtype (none
     * exist in this codebase today; the branch is defensive against future
     * sealed-tree growth).
     */
    private fun typedPayloadOf(publish: PublishMessage): Any? =
        when (publish) {
            is PublishMessageV4<*> -> publish.payload
            is ControlPacketV5.Publish<*> -> publish.payload
            else -> null
        }
}
