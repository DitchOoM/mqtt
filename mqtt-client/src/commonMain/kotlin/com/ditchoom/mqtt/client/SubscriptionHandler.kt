package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IncomingPublish

/**
 * Handler for incoming publish messages on a subscribed topic.
 *
 * Two variants exist:
 * - [Blocking]: Non-suspending callback, suitable for fast synchronous processing.
 * - [Async]: Suspending callback, for I/O or coroutine-based processing.
 *
 * The [IncomingPublish] payload buffer is scoped to the callback invocation.
 * Do NOT capture or store the buffer reference beyond the callback.
 * Copy the payload bytes if you need them later.
 */
sealed interface SubscriptionHandler {
    /**
     * Non-suspending handler. The callback runs inline on the reader coroutine,
     * so it must be fast and non-blocking.
     */
    fun interface Blocking : SubscriptionHandler {
        fun onPublish(publish: IncomingPublish)
    }

    /**
     * Suspending handler. The callback can perform I/O or other suspend operations.
     */
    fun interface Async : SubscriptionHandler {
        suspend fun onPublish(publish: IncomingPublish)
    }
}
