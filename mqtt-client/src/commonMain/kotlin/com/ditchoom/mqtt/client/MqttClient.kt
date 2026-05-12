package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MqttClient(
    internal val connectivityManager: ConnectivityManager,
    internal val scope: CoroutineScope,
) {
    internal val processor: ControlPacketProcessor get() = connectivityManager.processor
    val broker: MqttBroker = connectivityManager.broker

    /** Observable connection state. Collect to track Connected/Disconnected/Reconnecting/Failed. */
    val connectionState: StateFlow<ConnectionState> get() = connectivityManager.connectionState
    val packetFactory: ControlPacketFactory = connectivityManager.broker.connectionRequest.controlPacketFactory

    private var connectionJob: Job? = null

    suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? = connectivityManager.currentConnack()

    suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        var c = currentConnectionAcknowledgment()
        if (c == null) {
            c = connectivityManager.connectionBroadcastChannel.take(1).first()
        }
        return c
    }

    suspend fun pingCount() = connectivityManager.processor.pingCount

    suspend fun pingResponseCount() = connectivityManager.processor.pingResponseCount

    suspend fun sendQueuedPublishMessage(
        packetId: Int,
        pubQos0: PublishMessage?,
    ) {
        val pub =
            if (pubQos0 != null && pubQos0.qualityOfService == QualityOfService.AT_MOST_ONCE) {
                pubQos0
            } else {
                connectivityManager.persistence.getPubWithPacketId(
                    connectivityManager.broker,
                    packetId,
                )
            } ?: return
        processor.publish(pub, false)
    }

    suspend fun publish(
        topicName: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        payload: ReadBuffer? = null,
        retain: Boolean = false,
    ): PublishResult =
        publish(
            packetFactory.publish(
                topicName = TopicName.fromOrThrow(topicName),
                qos = qos,
                retain = retain,
                payload = payload,
            ),
        )

    suspend fun publish(pub: PublishMessage): PublishResult {
        val prepared = processor.preparePublish(pub)
        val result = observePub(prepared)
        processor.sendPacket(prepared)
        return result
    }

    private fun observePub(publishMessage: PublishMessage): PublishResult {
        val packetId = publishMessage.packetIdentifier
        return when (publishMessage.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> PublishResult.QoS0Sent

            QualityOfService.AT_LEAST_ONCE -> {
                check(packetId != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val stateFlow = MutableStateFlow<QoS1State>(QoS1State.Queued)
                processor.qos1States[packetId] = stateFlow
                PublishResult.QoS1(packetId, stateFlow)
            }

            QualityOfService.EXACTLY_ONCE -> {
                check(packetId != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val stateFlow = MutableStateFlow<QoS2State>(QoS2State.Queued)
                processor.qos2States[packetId] = stateFlow
                PublishResult.QoS2(packetId, stateFlow)
            }
        }
    }

    /**
     * Observe incoming publishes matching [filter] as an untyped flow. Payloads are the raw
     * wire [PublishMessage] instances (each carries its own owned payload).
     */
    fun observe(filter: TopicFilter): Flow<PublishMessage> =
        processor.readChannel.filterIsInstance<PublishMessage>().filter {
            filter.matches(it.topic)
        }

    /**
     * Observe incoming publishes matching [filter] decoded through [payloadCodec]. Each emitted
     * value contains the owned typed payload — no buffer-lifecycle contract.
     */
    fun <P> observe(
        filter: TopicFilter,
        payloadCodec: Codec<P>,
    ): Flow<Pair<PublishMessage, P>> =
        observe(filter).map { pub ->
            val raw = pub.rawPayload()
            val ctx = DecodeContext.Empty
            val decoded =
                if (raw == null || raw.remaining() == 0) {
                    payloadCodec.decode(ReadBuffer.EMPTY_BUFFER, ctx)
                } else {
                    val slice = raw.slice()
                    try {
                        payloadCodec.decode(slice, ctx)
                    } finally {
                        slice.freeIfNeeded()
                    }
                }
            pub to decoded
        }

    suspend fun sendQueuedSubscribeMessage(packetId: Int) {
        val sub =
            connectivityManager.persistence.getSubWithPacketId(connectivityManager.broker, packetId) ?: return
        processor.subscribe(sub, false)
    }

    suspend fun subscribe(
        topicFilter: String,
        maxQos: QualityOfService,
    ): SubscribeOperation = subscribe(packetFactory.subscribe(TopicFilter.fromOrThrow(topicFilter), maxQos))

    suspend fun subscribe(subscriptions: Set<ISubscription>): SubscribeOperation = subscribe(packetFactory.subscribe(subscriptions))

    suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation = observeSub(processor.subscribe(sub))

    /**
     * Subscribe with a callback handler for incoming publishes.
     *
     * The handler receives a [PublishMessage] whose payload is owned (no scope contract);
     * capture it freely. For typed payloads, prefer the overload that accepts a
     * `ReadBuffer.() -> P` decode lambda.
     */
    suspend fun subscribe(
        topicFilter: String,
        maxQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        handler: SubscriptionHandler,
    ): SubscribeOperation =
        subscribe(
            packetFactory.subscribe(TopicFilter.fromOrThrow(topicFilter), maxQos),
            handler,
        )

    suspend fun subscribe(
        sub: ISubscribeRequest,
        handler: SubscriptionHandler,
    ): SubscribeOperation {
        for (subscription in sub.subscriptions) {
            processor.publishDispatcher.subscribe(subscription.topicFilter, handler)
        }
        return observeSub(processor.subscribe(sub))
    }

    private fun observeSub(subscribeRequestSent: ISubscribeRequest): SubscribeOperation {
        val map = subscribeRequestSent.subscriptions.associateWith { observe(it.topicFilter) }
        return SubscribeOperation(
            subscribeRequestSent.packetIdentifier,
            map,
            scope.async {
                processor.awaitIncomingPacketId(
                    subscribeRequestSent.packetIdentifier,
                    ISubscribeAcknowledgement.CONTROL_PACKET_VALUE,
                ) as ISubscribeAcknowledgement
            },
        )
    }

    suspend fun sendQueuedUnsubscribeMessage(packetId: Int) {
        val unsub =
            connectivityManager.persistence.getUnsubWithPacketId(connectivityManager.broker, packetId) ?: return
        processor.unsubscribe(unsub, false)
    }

    suspend fun unsubscribe(topicFilter: String): UnsubscribeOperation =
        unsubscribe(packetFactory.unsubscribe(TopicFilter.fromOrThrow(topicFilter)))

    suspend fun unsubscribe(subscriptions: Set<TopicFilter>): UnsubscribeOperation = unsubscribe(packetFactory.unsubscribe(subscriptions))

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
        for (topic in unsub.topics) {
            processor.publishDispatcher.unsubscribe(topic)
        }
        return observeUnsubscribe(processor.unsubscribe(unsub))
    }

    private fun observeUnsubscribe(unsubscribeRequestSent: IUnsubscribeRequest): UnsubscribeOperation =
        UnsubscribeOperation(
            unsubscribeRequestSent.packetIdentifier,
            scope.async {
                processor.awaitIncomingPacketId(
                    unsubscribeRequestSent.packetIdentifier,
                    IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE,
                ) as IUnsubscribeAcknowledgment
            },
        )

    suspend fun sendDisconnect() {
        connectivityManager.sendDisconnect()
    }

    /**
     * Shuts down the client and disconnects from the broker.
     *
     * @param sendDisconnect Whether to send a DISCONNECT packet before closing
     * @param drain If true, waits for in-flight QoS 1/2 acknowledgments to complete
     *   before disconnecting. This ensures the persistence queue is clean and no
     *   messages need to be retransmitted on the next connection.
     */
    suspend fun shutdown(
        sendDisconnect: Boolean = true,
        drain: Boolean = false,
    ) {
        connectivityManager.shutdown(sendDisconnect, drain)
        connectionJob?.cancel()
        connectionJob = null
    }

    /**
     * Publish a typed payload through [payloadCodec]. The codec runs eagerly on the calling
     * thread; the resulting wire bytes are wrapped in a `PublishMessage` and queued. Routing
     * through `Codec<P>` (rather than a raw write lambda) means the same generated codec
     * that validates spec compliance produces the wire bytes.
     */
    suspend fun <P> publish(
        topic: String,
        payload: P,
        payloadCodec: Codec<P>,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        retain: Boolean = false,
    ): PublishResult {
        val encoded = eagerEncode(payload, payloadCodec)
        val pub =
            packetFactory.publish(
                topicName = TopicName.fromOrThrow(topic),
                qos = qos,
                retain = retain,
                payload = encoded,
            )
        return publish(pub)
    }

    /**
     * Subscribe with a typed handler. The dispatcher hands [payloadCodec] a wire-payload
     * `ReadBuffer` slice and passes both the [PublishMessage] (for metadata) and the
     * decoded value to [handler]. Auto-ack on handler return — if the handler throws, the
     * message is NOT acknowledged and will be redelivered on reconnect.
     */
    suspend fun <P> subscribe(
        topicFilter: String,
        payloadCodec: Codec<P>,
        maxQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        handler: suspend (PublishMessage, P) -> Unit,
    ): SubscribeOperation {
        val filter = TopicFilter.fromOrThrow(topicFilter)
        val sub = packetFactory.subscribe(filter, maxQos)
        processor.publishDispatcher.subscribeTyped(
            filter,
            SubscriberEntry.Typed({ payloadCodec.decode(this, DecodeContext.Empty) }, handler),
        )
        return observeSub(processor.subscribe(sub))
    }

    /**
     * Encode [value] through [codec] into a fresh read-positioned [ReadBuffer]. Picks an
     * exact-size allocation when `codec.wireSize` returns `Exact`; otherwise grows by
     * doubling on overflow up to [MAX_BACKPATCH_BYTES] (a codec that returns BackPatch
     * but cannot fit within that cap is a configuration error — callers needing larger
     * payloads should supply a codec with an exact `wireSize`).
     */
    private fun <P> eagerEncode(
        value: P,
        codec: Codec<P>,
    ): ReadBuffer {
        val ctx = EncodeContext.Empty
        return when (val size = codec.wireSize(value, ctx)) {
            is WireSize.Exact -> {
                val buf = BufferFactory.Default.allocate(size.bytes)
                codec.encode(buf, value, ctx)
                buf.resetForRead()
                buf
            }
            WireSize.BackPatch -> encodeBackPatch(value, codec, ctx)
        }
    }

    private fun <P> encodeBackPatch(
        value: P,
        codec: Codec<P>,
        ctx: EncodeContext,
    ): ReadBuffer {
        var capacity = INITIAL_BACKPATCH_BYTES
        while (capacity <= MAX_BACKPATCH_BYTES) {
            val buf = BufferFactory.Default.allocate(capacity)
            try {
                codec.encode(buf, value, ctx)
                buf.resetForRead()
                return buf
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // Buffer overflow surfaces as different platform-specific exceptions
                // (java.nio.BufferOverflowException on JVM, IndexOutOfBoundsException on
                // K/N, etc.). Match by simpleName the same way MqttCodec.peekFrameSize
                // does, so we grow on overflow but propagate genuine encode errors.
                when (e::class.simpleName) {
                    "BufferOverflowException",
                    "IndexOutOfBoundsException",
                    "ArrayIndexOutOfBoundsException",
                    -> {
                        buf.freeNativeMemory()
                        capacity *= 2
                    }
                    else -> throw e
                }
            }
        }
        error(
            "Encoded payload exceeded $MAX_BACKPATCH_BYTES bytes; supply a Codec<P> " +
                "with an Exact wireSize for larger payloads.",
        )
    }

    /**
     * Returns in-flight publish operations that survived a process restart.
     * Each has a [PublishResult] with a [StateFlow] reconstructed from the persisted state.
     * Useful for recovering UI state or tracking delivery after restart.
     */
    suspend fun pendingPublishes(): List<PublishResult> {
        val results = mutableListOf<PublishResult>()
        for ((packetId, stateFlow) in processor.qos1States) {
            results += PublishResult.QoS1(packetId, stateFlow)
        }
        for ((packetId, stateFlow) in processor.qos2States) {
            results += PublishResult.QoS2(packetId, stateFlow)
        }
        return results
    }

    internal fun isStopped() = connectionJob?.isActive != true

    suspend fun connectionCount(): Long = connectivityManager.connectionCount

    suspend fun connectionAttempts(): Long = connectivityManager.connectionAttempts

    companion object {
        /**
         * Starts a client for [broker] and suspends until the first full handshake pass
         * completes (CONNACK received OR all of [MqttBroker.connectionOps] exhausted).
         *
         * After the first session ends — either because the peer closed the TCP socket or
         * because the user called [sendDisconnect] — an outer loop here re-invokes
         * [ConnectivityManager.run] so the session-resume / "stay connected" pattern works
         * without the caller having to wrap [connectSingle] in socket's
         * [com.ditchoom.socket.transport.ReconnectingConnection]. The loop exits only when
         * the client's [CoroutineScope] is cancelled (e.g. via [shutdown]) or when the next
         * handshake pass throws a non-retryable exception.
         *
         * [connectSingle] opens one transport for one [MqttConnectionOptions]; option
         * iteration is [ConnectivityManager]'s job so each attempt is counted in
         * [connectionAttempts].
         */
        suspend fun start(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Client")),
            broker: MqttBroker,
            persistence: Persistence,
            connectSingle: suspend (MqttConnectionOptions) -> Connection<ControlPacket> =
                com.ditchoom.mqtt.client.net
                    .defaultSingleConnection(broker),
        ): MqttClient {
            val cm = ConnectivityManager(persistence, broker, connectSingle)
            val client = MqttClient(cm, scope)
            client.connectionJob =
                scope.launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        try {
                            cm.run()
                            // Clean session end (sendDisconnect → server FIN). Reconnect.
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") _: Throwable,
                        ) {
                            // Connection error — back off, then retry.
                            kotlinx.coroutines.delay(reconnectBackoff)
                        }
                    }
                }
            cm.firstAttemptComplete.await()
            return client
        }

        /**
         * Delay between reconnect attempts. Small, fixed — callers that want sophisticated
         * backoff / network-aware retry can wrap [connectSingle] in their own factory.
         */
        private val reconnectBackoff = kotlin.time.Duration.parse("PT1S")

        private const val INITIAL_BACKPATCH_BYTES = 256
        private const val MAX_BACKPATCH_BYTES = 16 * 1024 * 1024
    }
}
