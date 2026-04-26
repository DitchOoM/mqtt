package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage

/**
 * Handler for incoming publish messages on a subscribed topic.
 *
 * Two variants exist:
 * - [Blocking]: Non-suspending callback, suitable for fast synchronous processing.
 * - [Async]: Suspending callback, for I/O or coroutine-based processing.
 *
 * The [PublishMessage] owns its decoded payload — callers can retain the message without
 * worrying about buffer-lifecycle contracts. For typed payloads, prefer the typed subscribe
 * overload on [MqttClient] which invokes a `ReadBuffer.() -> P` decode lambda and passes
 * the decoded value alongside the [PublishMessage].
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
