package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.TopicFilter

/**
 * Dispatches incoming publish messages to registered [SubscriberEntry] instances
 * using a [TopicTrie] for O(segments) wildcard matching.
 *
 * Each entry captures its own payload decoding rule (typed subscribers bundle a
 * [com.ditchoom.mqtt.codec.PayloadCodec] plus handler; untyped subscribers accept the raw
 * [PublishMessage]). Since payloads are owned by the decoder call site (no scope
 * invalidation), multi-subscriber dispatch simply runs each entry's dispatch in sequence.
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

    /** Register a typed subscriber with its own payload codec + handler. */
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
        for (entry in entries) {
            entry.dispatch(publish)
        }
        return true
    }

    /** Returns true if any handler would match the given [PublishMessage]. */
    fun hasMatch(publish: PublishMessage): Boolean = trie.hasMatch(publish.topic)

    /** Returns true if no handlers are registered. */
    fun isEmpty(): Boolean = trie.isEmpty()

    /** Remove all handlers. */
    fun clear() = trie.clear()
}
