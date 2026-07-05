package com.ditchoom.mqtt.client

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Per-connection topic→codec registry. `MqttClient.subscribe<P>(filter, codec, handler)`
 * registers the codec before sending SUBSCRIBE so the dispatcher's per-message
 * topic-router lambda can resolve it inside the wire-frame decode without an
 * intermediate copy.
 *
 * **Concurrency**: copy-on-write [AtomicReference] of an immutable map. Reads
 * are lock-free (`load()` is a volatile read on every platform); writes
 * CAS-loop until they win, so concurrent register/unregister are safe.
 *
 * Reads are hot (every PUBLISH decode); writes are rare (subscribe / unsubscribe
 * happens at most dozens of times per connection lifetime). The linear filter
 * walk on each lookup is fine at <100 subscriptions; a trie-based index is a
 * later optimization if profiling demands it.
 *
 * **Coroutine-aware**: deliberately NOT an actor — the decode path is
 * synchronous and cannot suspend. Mutex.withLock would force suspension and
 * defeat the codec interface's non-suspending contract. The atomic snapshot
 * pattern is what fits.
 *
 * **Ordering invariant**: `MqttClient.subscribe` MUST call [register] before
 * writing SUBSCRIBE to the wire. The broker can route a matching PUBLISH back
 * as soon as it processes SUBSCRIBE, so the codec has to be visible to the
 * decoder before that round-trip starts. This is a coroutine-level invariant
 * enforced in [MqttClient.subscribe], not a property the registry can verify.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TopicCodecRegistry {
    private val entries: AtomicReference<Map<TopicFilter, Codec<out Payload>>> =
        AtomicReference(emptyMap())

    fun register(
        filter: TopicFilter,
        codec: Codec<out Payload>,
    ) {
        entries.update { it + (filter to codec) }
    }

    fun unregister(filter: TopicFilter) {
        entries.update { it - filter }
    }

    /**
     * Returns the codec whose filter matches [topic], or `null` when nothing
     * is registered. Iteration order is insertion order; if multiple
     * registered filters match, the first registered wins. TODO(B-4b):
     * detect overlapping-filter registrations at [register] time and throw
     * if the second registration would shadow a different codec result
     * type. Today consumers are responsible for not registering
     * overlapping filters with different codec result types.
     */
    fun codecForTopicName(topic: TopicName): Codec<out Payload>? {
        val snapshot = entries.load()
        for ((filter, codec) in snapshot) {
            if (filter.matches(topic)) return codec
        }
        return null
    }
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val current = load()
        val next = transform(current)
        if (compareAndSet(current, next)) return
    }
}
