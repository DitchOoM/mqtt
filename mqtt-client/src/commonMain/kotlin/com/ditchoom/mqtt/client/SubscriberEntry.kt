package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage

/**
 * Existential wrapper bundling a [PayloadCodec] with a typed handler. The payload type `P`
 * is captured inside the entry; the dispatcher pattern-matches on the sealed hierarchy and
 * invokes the appropriate dispatch method, so the trie can hold `SubscriberEntry<*>` without
 * casts at call sites.
 */
internal sealed class SubscriberEntry {
    /** Handler-only entry; no codec, no per-subscriber decode. */
    class Untyped(
        private val handler: suspend (PublishMessage) -> Unit,
    ) : SubscriberEntry() {
        suspend fun dispatch(publish: PublishMessage) = handler(publish)
    }

    /**
     * Typed entry bundling a [PayloadCodec] and a handler. The dispatcher hands this
     * entry a fresh [ReadBuffer] slice of the wire payload per invocation — the codec
     * decodes directly from the slice. No intermediate allocation; the slice's
     * independent position/limit means subscribers don't interfere with one another.
     */
    class Typed<P>(
        private val codec: PayloadCodec<P>,
        private val handler: suspend (PublishMessage, P) -> Unit,
    ) : SubscriberEntry() {
        suspend fun dispatch(
            publish: PublishMessage,
            payloadSlice: ReadBuffer,
        ) = handler(publish, codec.decode(payloadSlice))
    }
}
