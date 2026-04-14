package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage

/**
 * Handler for incoming publish messages on a subscribed topic.
 *
 * Two variants exist:
 * - [Blocking]: Non-suspending callback, suitable for fast synchronous processing.
 * - [Async]: Suspending callback, for I/O or coroutine-based processing.
 *
 * The [PublishMessage] payload is read inside [PublishMessage.usePayload]; the
 * receiver buffer is valid only inside that block. To retain payload bytes past
 * the callback, allocate your own buffer inside `usePayload` and copy into it.
 */
sealed interface SubscriptionHandler {
    /**
     * Non-suspending handler. The callback runs inline on the reader coroutine,
     * so it must be fast and non-blocking.
     */
    fun interface Blocking : SubscriptionHandler {
        fun onPublish(publish: PublishMessage)
    }

    /**
     * Suspending handler. The callback can perform I/O or other suspend operations.
     */
    fun interface Async : SubscriptionHandler {
        suspend fun onPublish(publish: PublishMessage)
    }
}
