package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.TopicFilter

/**
 * Dispatches incoming publish messages to registered [SubscriptionHandler]s
 * using a [TopicTrie] for O(segments) wildcard matching.
 *
 * Lifecycle:
 * 1. User calls `subscribe(filter, handler)` — handler is registered in the trie
 * 2. Incoming publishes are dispatched via [dispatch] — all matching handlers are invoked
 * 3. After all handlers return, [PublishMessage.invalidateScope] fires so any stashed
 *    payload buffer reference becomes unreadable.
 *
 * Returns whether any handler matched, so the caller can fall through to the
 * legacy `observe()` flow for unmatched messages.
 */
internal class PublishDispatcher {
    private val trie = TopicTrie<SubscriptionHandler>()

    /** Register a handler for the given topic filter. Returns any previous handler. */
    fun subscribe(
        filter: TopicFilter,
        handler: SubscriptionHandler,
    ): SubscriptionHandler? = trie.insert(filter, handler)

    /** Remove the handler for the given topic filter. */
    fun unsubscribe(filter: TopicFilter): SubscriptionHandler? = trie.remove(filter)

    /**
     * Dispatch an incoming publish to all matching handlers. Invalidates the
     * payload scope after every handler completes so that stashed references
     * fail loudly on use-after-scope.
     *
     * @return true if at least one handler was invoked
     */
    suspend fun dispatch(publish: PublishMessage): Boolean {
        val handlers = trie.matchAll(publish.topic)
        if (handlers.isEmpty()) return false
        try {
            for (handler in handlers) {
                when (handler) {
                    is SubscriptionHandler.Blocking -> handler.onPublish(publish)
                    is SubscriptionHandler.Async -> handler.onPublish(publish)
                }
            }
        } finally {
            publish.invalidateScope()
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
