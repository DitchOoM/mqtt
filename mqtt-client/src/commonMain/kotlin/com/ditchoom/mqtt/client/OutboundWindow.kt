package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.PacketId
import com.ditchoom.mqtt.controlpacket.PacketIdParse
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns every outbound QoS 1/2 PUBLISH from admission to final acknowledgement. A single
 * coroutine ([run]) consumes [events], and the window state exists only inside that coroutine,
 * so persistence of outbound publishes, the send quota and the delivery state flows change in
 * one ordered place with no shared mutable state.
 *
 * A publish is admitted only while a session is established and fewer than that session's
 * [InFlightLimit] publishes are unacknowledged; until then the caller suspends, FIFO. Admitted
 * and resent packets reach the writer through [outbound], which is drained whenever a new
 * session starts, so nothing queued for a dead connection reaches the next one.
 */
internal class OutboundWindow(
    private val broker: MqttBroker,
    private val persistence: Persistence,
    private val configuredLimit: InFlightLimit,
) {
    private val events = Channel<Event>(Channel.UNLIMITED, onUndeliveredElement = Event::abandon)
    private val wire = Channel<ControlPacket>(Channel.UNLIMITED)

    val outbound: ReceiveChannel<ControlPacket> get() = wire

    suspend fun publish(
        publish: PublishMessage,
        tracking: Tracking,
    ): PublishResult {
        val admitted = CompletableDeferred<PublishResult>()
        events.send(Event.Admit(publish, tracking, admitted))
        try {
            return admitted.await()
        } catch (e: CancellationException) {
            admitted.cancel(e)
            throw e
        }
    }

    /** Suspends until the window has adopted the session, so the writer starts on a clean [outbound]. */
    suspend fun sessionStarted(
        receiveMaximum: Int,
        resumption: Resumption,
    ) {
        val adopted = CompletableDeferred<Unit>()
        events.send(Event.SessionStarted(configuredLimit.constrainedBy(receiveMaximum), resumption, adopted))
        adopted.await()
    }

    /** Non-suspending so connection teardown always completes; once the window has stopped there is nothing to tell. */
    fun sessionEnded() {
        events.trySend(Event.SessionEnded)
    }

    suspend fun written(packet: ControlPacket) = events.send(Event.Written(packet))

    suspend fun acknowledged(ack: IPublishAcknowledgment) = events.send(Event.Acknowledged(ack))

    suspend fun received(packetId: Int) = events.send(Event.Received(packetId))

    suspend fun released(packetId: Int) = events.send(Event.Released(packetId))

    suspend fun completed(comp: IPublishComplete) = events.send(Event.Completed(comp))

    suspend fun snapshot(): List<PublishResult> {
        val reply = CompletableDeferred<List<PublishResult>>()
        events.send(Event.Snapshot(reply))
        return reply.await()
    }

    suspend fun run() {
        val window = Window()
        try {
            for (event in events) window.handle(event)
        } finally {
            window.abandonWaiting()
            events.cancel()
        }
    }

    private inner class Window {
        /** Every unacknowledged publish this client knows about; each holds one unit of quota. */
        private val tracked = LinkedHashMap<PacketId, Tracking>()

        /** Persisted packets of a resumed session still waiting for quota, in original send order. */
        private val resendBacklog = ArrayDeque<Resend>()
        private val waiting = ArrayDeque<Event.Admit>()
        private var link: Link = Link.Offline

        suspend fun handle(event: Event) {
            when (event) {
                is Event.Admit -> waiting.addLast(event)
                is Event.SessionStarted -> {
                    while (wire.tryReceive().isSuccess) Unit
                    resendBacklog.clear()
                    when (val resumption = event.resumption) {
                        Resumption.Fresh -> discard { true }
                        is Resumption.Resumed -> resume(resumption.persisted)
                    }
                    link = Link.Online(event.limit)
                    event.adopted.complete(Unit)
                }
                Event.SessionEnded -> link = Link.Offline
                is Event.Written ->
                    when (val packet = event.packet) {
                        is PublishMessage -> onTracked(packet.packetIdentifier) { _, tracking -> tracking.written() }
                        else -> Unit
                    }
                is Event.Acknowledged -> {
                    persistence.ackPub(broker, event.ack)
                    onTracked(event.ack.packetIdentifier) { id, tracking ->
                        if (tracking is Tracking.AtLeastOnce) {
                            tracking.state.value = QoS1State.Acknowledged(event.ack)
                            release(id)
                        }
                    }
                }
                is Event.Received ->
                    onTracked(event.packetId) { _, tracking ->
                        if (tracking is Tracking.ExactlyOnce) tracking.state.value = QoS2State.Received
                    }
                is Event.Released ->
                    onTracked(event.packetId) { _, tracking ->
                        if (tracking is Tracking.ExactlyOnce) tracking.state.value = QoS2State.Released
                    }
                is Event.Completed -> {
                    persistence.ackPubComplete(broker, event.comp)
                    onTracked(event.comp.packetIdentifier) { id, tracking ->
                        if (tracking is Tracking.ExactlyOnce) {
                            tracking.state.value = QoS2State.Complete(event.comp)
                            release(id)
                        }
                    }
                }
                is Event.Snapshot -> event.reply.complete(tracked.map { (id, tracking) -> tracking.result(id) })
            }
            fillQuota()
        }

        fun abandonWaiting() {
            waiting.forEach { it.abandon() }
            waiting.clear()
        }

        private fun onWire(): Int = tracked.size - resendBacklog.size

        /** Spends free quota on the resend backlog first, then on waiting publishers in arrival order. */
        private suspend fun fillQuota() {
            val limit =
                when (val current = link) {
                    Link.Offline -> return
                    is Link.Online -> current.limit.value
                }
            while (resendBacklog.isNotEmpty() && onWire() < limit) {
                wire.send(resendBacklog.removeFirst().packet)
            }
            while (resendBacklog.isEmpty() && waiting.isNotEmpty() && onWire() < limit) {
                admit(waiting.removeFirst())
            }
        }

        private suspend fun admit(next: Event.Admit) {
            if (next.admitted.isCancelled) return
            val id =
                try {
                    PacketId(persistence.writePubGetPacketId(broker, next.publish))
                } catch (e: CancellationException) {
                    next.abandon()
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    next.admitted.completeExceptionally(e)
                    return
                }
            tracked[id] = next.tracking
            wire.send(next.publish.maybeCopyWithNewPacketIdentifier(id.value))
            next.admitted.complete(next.tracking.result(id))
        }

        private fun release(id: PacketId) {
            tracked.remove(id)
            resendBacklog.removeAll { it.id == id }
        }

        /** The persisted session is the authority: what it holds is resent and tracked, the rest is discarded. */
        private suspend fun resume(persisted: Collection<ControlPacket>) {
            val resumed = LinkedHashMap<PacketId, Tracking>()
            for (packet in persisted) {
                when (val claim = claimOf(packet)) {
                    is Claim.Quota -> {
                        resumed[claim.id] = tracked[claim.id] ?: claim.restored
                        resendBacklog.addLast(Resend(claim.id, packet))
                    }
                    Claim.None -> wire.send(packet)
                }
            }
            discard { it !in resumed }
            tracked.putAll(resumed)
        }

        private fun claimOf(packet: ControlPacket): Claim =
            when (packet) {
                is PublishMessage ->
                    when (packet.qualityOfService) {
                        QualityOfService.AT_MOST_ONCE -> Claim.None
                        QualityOfService.AT_LEAST_ONCE -> Claim.of(packet, Tracking.AtLeastOnce(QoS1State.Sent))
                        QualityOfService.EXACTLY_ONCE -> Claim.of(packet, Tracking.ExactlyOnce(QoS2State.Sent))
                    }
                is IPublishRelease -> Claim.of(packet, Tracking.ExactlyOnce(QoS2State.Released))
                else -> Claim.None
            }

        private inline fun discard(predicate: (PacketId) -> Boolean) {
            val iterator = tracked.entries.iterator()
            while (iterator.hasNext()) {
                val (id, tracking) = iterator.next()
                if (predicate(id)) {
                    tracking.sessionDiscarded()
                    iterator.remove()
                }
            }
        }

        private inline fun onTracked(
            packetId: Int,
            action: (PacketId, Tracking) -> Unit,
        ) {
            when (val parsed = PacketId.parse(packetId)) {
                is PacketIdParse.Valid -> tracked[parsed.id]?.let { action(parsed.id, it) }
                is PacketIdParse.OutOfRange -> Unit
            }
        }
    }

    private class Resend(
        val id: PacketId,
        val packet: ControlPacket,
    )

    /** Whether a persisted packet occupies send quota when its session resumes. */
    private sealed interface Claim {
        data object None : Claim

        class Quota(
            val id: PacketId,
            val restored: Tracking,
        ) : Claim

        companion object {
            fun of(
                packet: ControlPacket,
                restored: Tracking,
            ): Claim =
                when (val parsed = PacketId.parse(packet.packetIdentifier)) {
                    is PacketIdParse.Valid -> Quota(parsed.id, restored)
                    is PacketIdParse.OutOfRange -> None
                }
        }
    }

    private sealed interface Link {
        data object Offline : Link

        data class Online(
            val limit: InFlightLimit,
        ) : Link
    }

    private sealed interface Event {
        /** Releases a caller waiting on this event when the window stops before handling it. */
        fun abandon() = Unit

        class Admit(
            val publish: PublishMessage,
            val tracking: Tracking,
            val admitted: CompletableDeferred<PublishResult>,
        ) : Event {
            override fun abandon() {
                admitted.cancel()
            }
        }

        class SessionStarted(
            val limit: InFlightLimit,
            val resumption: Resumption,
            val adopted: CompletableDeferred<Unit>,
        ) : Event {
            override fun abandon() {
                adopted.cancel()
            }
        }

        data object SessionEnded : Event

        class Written(
            val packet: ControlPacket,
        ) : Event

        class Acknowledged(
            val ack: IPublishAcknowledgment,
        ) : Event

        class Received(
            val packetId: Int,
        ) : Event

        class Released(
            val packetId: Int,
        ) : Event

        class Completed(
            val comp: IPublishComplete,
        ) : Event

        class Snapshot(
            val reply: CompletableDeferred<List<PublishResult>>,
        ) : Event {
            override fun abandon() {
                reply.cancel()
            }
        }
    }
}

/** How a newly established session relates to the client's in-flight publishes. */
internal sealed interface Resumption {
    /** The broker holds no session: every in-flight publish is discarded. */
    data object Fresh : Resumption

    /** The broker resumed the session: [persisted] is resent in order and stays in flight. */
    class Resumed(
        val persisted: Collection<ControlPacket>,
    ) : Resumption
}

/** Delivery tracking for one outbound publish; the subtype is fixed by the publish's QoS. */
internal sealed interface Tracking {
    fun result(id: PacketId): PublishResult

    fun written()

    fun sessionDiscarded()

    class AtLeastOnce(
        initial: QoS1State = QoS1State.Queued,
    ) : Tracking {
        val state = MutableStateFlow(initial)

        override fun result(id: PacketId): PublishResult = PublishResult.QoS1(id, state)

        override fun written() {
            state.compareAndSet(QoS1State.Queued, QoS1State.Sent)
        }

        override fun sessionDiscarded() {
            state.value = QoS1State.SessionDiscarded
        }
    }

    class ExactlyOnce(
        initial: QoS2State = QoS2State.Queued,
    ) : Tracking {
        val state = MutableStateFlow(initial)

        override fun result(id: PacketId): PublishResult = PublishResult.QoS2(id, state)

        override fun written() {
            state.compareAndSet(QoS2State.Queued, QoS2State.Sent)
        }

        override fun sessionDiscarded() {
            state.value = QoS2State.SessionDiscarded
        }
    }
}
