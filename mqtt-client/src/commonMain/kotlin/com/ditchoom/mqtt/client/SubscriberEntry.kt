package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.payloadAsReadBufferOrNull

/**
 * Existential wrapper bundling a [PayloadCodec] with a typed handler. The payload type `P`
 * is captured inside the entry; the dispatcher interacts through the untyped [dispatch]
 * method only, so the trie can hold `SubscriberEntry<*>` without casts at call sites.
 *
 * On dispatch:
 * - Untyped entries (handler-only) receive the wire-decoded [PublishMessage] directly.
 * - Typed entries re-decode the payload bytes through the subscriber's [PayloadCodec] and
 *   pass both the (untyped) [PublishMessage] and the decoded typed payload to the handler.
 */
internal sealed class SubscriberEntry {
    abstract suspend fun dispatch(publish: PublishMessage)

    /** Handler-only entry; no codec, no per-subscriber decode. */
    class Untyped(
        private val handler: suspend (PublishMessage) -> Unit,
    ) : SubscriberEntry() {
        override suspend fun dispatch(publish: PublishMessage) = handler(publish)
    }

    /**
     * Typed entry bundling a [PayloadCodec] and a handler. Each subscriber's codec runs in
     * isolation — star-projection on the trie is sound because `P` appears only inside this
     * class.
     */
    class Typed<P>(
        private val codec: PayloadCodec<P>,
        private val handler: suspend (PublishMessage, P) -> Unit,
    ) : SubscriberEntry() {
        override suspend fun dispatch(publish: PublishMessage) {
            val payloadBytes: ReadBuffer? = publish.payloadAsReadBufferOrNull()
            val decoded =
                if (payloadBytes == null) {
                    // Empty payload — feed an empty buffer to the codec so it can decide.
                    codec.decode(BufferFactory.Default.allocate(0))
                } else {
                    payloadBytes.position(0)
                    codec.decode(payloadBytes)
                }
            handler(publish, decoded)
        }
    }
}
