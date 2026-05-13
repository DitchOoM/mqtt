package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.PublishMessage

/**
 * Dispatcher-side handler bundle. Under the zero-copy decode model the wire
 * payload is decoded once at the MqttCodec layer (using the codec registered
 * in [TopicCodecRegistry] for the topic), so the concrete [PublishMessage]
 * reaching dispatch already carries its typed payload. Entries no longer
 * decode — they just route the (already typed) message to a user handler.
 *
 * The typed entry casts `publish.payload` to the consumer's expected type at
 * dispatch time. A `ClassCastException` surfaces if a wildcard subscription's
 * codec doesn't match the registered codec that actually decoded the
 * incoming message — this is the documented behavior for "one codec per
 * topic" semantics.
 */
internal class SubscriberEntry<P>(
    private val handler: suspend (PublishMessage, P) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun dispatch(
        publish: PublishMessage,
        payload: Any,
    ) = handler(publish, payload as P)
}
