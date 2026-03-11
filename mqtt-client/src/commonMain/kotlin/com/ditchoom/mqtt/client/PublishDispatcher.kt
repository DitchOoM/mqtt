package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IncomingPublish
import com.ditchoom.mqtt.controlpacket.TopicFilter

/**
 * Dispatches incoming publish messages to registered [SubscriptionHandler]s
 * using a [TopicTrie] for O(segments) wildcard matching.
 *
 * Lifecycle:
 * 1. User calls `subscribe(filter, handler)` — handler is registered in the trie
 * 2. Incoming publishes are dispatched via [dispatch] — all matching handlers are invoked
 * 3. User calls `unsubscribe(filter)` — handler is removed
 *
 * Returns whether any handler matched, so the caller can fall through to the
 * legacy `observe()` flow for unmatched messages.
 */
internal class PublishDispatcher {
    private val trie = TopicTrie<SubscriptionHandler>()

    /** Register a handler for the given topic filter. Returns any previous handler. */
    fun subscribe(filter: TopicFilter, handler: SubscriptionHandler): SubscriptionHandler? =
        trie.insert(filter, handler)

    /** Remove the handler for the given topic filter. */
    fun unsubscribe(filter: TopicFilter): SubscriptionHandler? = trie.remove(filter)

    /**
     * Dispatch an incoming publish to all matching handlers.
     *
     * The payload buffer is wrapped in a [ScopedReadBuffer] that is invalidated
     * after all handlers complete. Any attempt to access the payload after the
     * handler returns throws [IllegalStateException].
     *
     * @param publish The incoming publish message (already adapted to [IncomingPublish])
     * @return true if at least one handler was invoked
     */
    suspend fun dispatch(publish: IncomingPublish): Boolean {
        val handlers = trie.matchAll(publish.topic)
        if (handlers.isEmpty()) return false

        // Wrap payload in a scoped guard
        val scopedPayload = publish.payload?.let { ScopedReadBuffer(it) }
        val scoped = if (scopedPayload != null) ScopedIncomingPublish(publish, scopedPayload) else publish

        try {
            for (handler in handlers) {
                when (handler) {
                    is SubscriptionHandler.Blocking -> handler.onPublish(scoped)
                    is SubscriptionHandler.Async -> handler.onPublish(scoped)
                }
            }
        } finally {
            // Invalidate the payload — any captured references will throw on access
            scopedPayload?.invalidate()
        }
        return true
    }

    /** Returns true if any handler would match the given [IPublishMessage]. */
    fun hasMatch(publish: IPublishMessage): Boolean = trie.hasMatch(publish.topic)

    /** Returns true if no handlers are registered. */
    fun isEmpty(): Boolean = trie.isEmpty()

    /** Remove all handlers. */
    fun clear() = trie.clear()
}
