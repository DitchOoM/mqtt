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
 * handed to each matching [SubscriberEntry.Typed].
 *
 * "One codec per topic" semantics: all subscribers matching an incoming
 * topic receive the same typed payload. If two subscribers register different
 * codec result types for overlapping filters, the type cast in
 * [SubscriberEntry.Typed] throws `ClassCastException`. Users who need
 * multi-codec semantics for the same topic should register
 * `OpaqueBytesHandleCodec` and re-decode in their handler.
 */
internal class PublishDispatcher {
    private val trie = TopicTrie<SubscriberEntry>()

    /** Register an untyped handler for the given topic filter. */
    fun subscribe(
        filter: TopicFilter,
        handler: SubscriptionHandler,
    ): SubscriberEntry? =
        trie.insert(
            filter,
            when (handler) {
                is SubscriptionHandler.Blocking ->
                    SubscriberEntry.Untyped { pub -> handler.onPublish(pub) }
                is SubscriptionHandler.Async ->
                    SubscriberEntry.Untyped { pub -> handler.onPublish(pub) }
            },
        )

    /** Register a typed subscriber. The codec lives in [TopicCodecRegistry]; this entry only routes. */
    fun <P> subscribeTyped(
        filter: TopicFilter,
        entry: SubscriberEntry.Typed<P>,
    ): SubscriberEntry? = trie.insert(filter, entry)

    /** Remove the handler for the given topic filter. */
    fun unsubscribe(filter: TopicFilter): SubscriberEntry? = trie.remove(filter)

    /**
     * Dispatch an incoming publish to all matching subscribers.
     *
     * @return true if at least one handler was invoked
     */
    suspend fun dispatch(publish: PublishMessage): Boolean {
        val entries = trie.matchAll(publish.topic)
        if (entries.isEmpty()) return false
        val typedPayload = typedPayloadOf(publish)
        for (entry in entries) {
            when (entry) {
                is SubscriberEntry.Untyped -> entry.dispatch(publish)
                is SubscriberEntry.Typed<*> -> {
                    if (typedPayload != null) {
                        entry.dispatch(publish, typedPayload)
                    }
                    // null typedPayload only occurs for non-v4/v5 PublishMessage subtypes
                    // (none exist today) — silently skip rather than crash. Typed
                    // subscribers expect a typed message; if the wire decode produced
                    // something else, the subscription model is mis-wired upstream.
                }
            }
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
