package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.Connection
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

class MqttClient internal constructor(
    internal val connectivityManager: ConnectivityManager,
    internal val scope: CoroutineScope,
    internal val publishCodecRegistry: TopicCodecRegistry = TopicCodecRegistry(),
    /**
     * Connection-default [BufferFactory] for eager publish-side encoding and for the
     * fallback decode path's `OpaqueBytesHandleCodec`. Consumers wanting a pool, a
     * deterministic factory, or a shared-memory allocator supply it at
     * [MqttClient.start] or per-call on [publish] / [publish<P>]. Defaults to
     * [BufferFactory.Default].
     */
    internal val bufferFactory: BufferFactory = BufferFactory.Default,
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

    /**
     * Convenience publish for callers that already have wire bytes (proxy / forwarding /
     * untyped log producers). Bytes are copied into a consumer-owned [com.ditchoom.buffer.PlatformBuffer]
     * via [bufferFactory] (defaults to the client's connection-default factory) and wrapped
     * in an [com.ditchoom.mqtt.controlpacket.OpaquePublishPayload]. Typed publishes go
     * through the [publish] / `<P>` overload below for codec-driven encoding.
     */
    suspend fun publish(
        topicName: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        payload: ReadBuffer? = null,
        retain: Boolean = false,
        bufferFactory: BufferFactory = this.bufferFactory,
    ): PublishResult {
        val remaining = payload?.remaining() ?: 0
        val dst = bufferFactory.allocate(remaining)
        if (remaining > 0 && payload != null) dst.write(payload)
        dst.resetForRead()
        val opaque =
            com.ditchoom.mqtt.controlpacket.OpaquePublishPayload(
                com.ditchoom.buffer.codec
                    .opaqueBytesFrom(dst),
            )
        val pub = buildPublishMessage(TopicName.fromOrThrow(topicName), qos, retain, opaque)
        return publish(pub)
    }

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
     * Extract the typed `payload` field from the concrete v4 / v5 PUBLISH variant. Mirrors
     * the helper in [PublishDispatcher]; lives here too so the typed [observe] overload can
     * surface the typed value without leaking dispatcher internals.
     */
    private fun typedPayloadOf(publish: PublishMessage): Any? =
        when (publish) {
            is com.ditchoom.mqtt3.controlpacket.PublishMessageV4<*> -> publish.payload
            is com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish<*> -> publish.payload
            else -> null
        }

    /**
     * Observe incoming publishes matching [filter] with the typed payload supplied via
     * [payloadCodec]. The codec is registered into the connection's
     * [TopicCodecRegistry] so [MqttCodec] can decode incoming PUBLISHes zero-copy at the
     * wire-frame layer; the typed value is then cast at the per-emit site here.
     *
     * Last-write-wins on the registry: if a different codec is already registered for
     * [filter], it's overwritten. Subscribers using both codecs would see the latter's
     * type and the former's handler would `ClassCastException` — documented "one codec
     * per topic" semantics.
     *
     * No untyped overload exists by design: every observer chooses its decode shape so
     * the cost of producing the typed value (zero-copy for Pattern #1 / field-by-field;
     * one wire-boundary copy for [com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec])
     * is visible at the call site rather than hidden in a default.
     */
    fun <P : Payload> observe(
        filter: TopicFilter,
        payloadCodec: Codec<P>,
    ): Flow<Pair<PublishMessage, P>> {
        publishCodecRegistry.register(filter, payloadCodec)
        return processor.readChannel
            .filterIsInstance<PublishMessage>()
            .filter { filter.matches(it.topic) }
            .map { pub ->
                @Suppress("UNCHECKED_CAST")
                val typed =
                    typedPayloadOf(pub) as? P
                        ?: error(
                            "Expected typed ${payloadCodec::class.simpleName} payload on ${pub::class.simpleName} " +
                                "for topic '${pub.topic}', but the actually-decoded payload doesn't match. " +
                                "Check for overlapping wildcard subscriptions registering different codec result types.",
                        )
                pub to typed
            }
    }

    suspend fun sendQueuedSubscribeMessage(packetId: Int) {
        val sub =
            connectivityManager.persistence.getSubWithPacketId(connectivityManager.broker, packetId) ?: return
        processor.subscribe(sub, false)
    }

    /**
     * Subscribe to [topicFilter] with the typed codec [payloadCodec]; collect incoming
     * publishes via [SubscribeOperation.subscriptions] or the operation's flow.
     *
     * Registers [payloadCodec] in this client's [TopicCodecRegistry] so [MqttCodec]
     * decodes wire bytes zero-copy at the framing boundary (Pattern #1 when the codec
     * uses the wire buffer's native handle; field-by-field structured codecs are also
     * zero bulk-copy). For raw-bytes consumers, pass [com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec]
     * — one explicit copy at the wire boundary, no hidden defaults.
     */
    suspend fun <P : Payload> subscribe(
        topicFilter: String,
        payloadCodec: Codec<P>,
        maxQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
    ): SubscribeOperation<P> {
        val filter = TopicFilter.fromOrThrow(topicFilter)
        publishCodecRegistry.register(filter, payloadCodec)
        return observeSub(processor.subscribe(packetFactory.subscribe(filter, maxQos)), payloadCodec)
    }

    /** Multi-topic subscribe; one codec applies to every subscription in the SUB packet. */
    suspend fun <P : Payload> subscribe(
        subscriptions: Set<ISubscription>,
        payloadCodec: Codec<P>,
    ): SubscribeOperation<P> = subscribe(packetFactory.subscribe(subscriptions), payloadCodec)

    /** Multi-topic subscribe variant taking a pre-built [ISubscribeRequest]. */
    suspend fun <P : Payload> subscribe(
        sub: ISubscribeRequest,
        payloadCodec: Codec<P>,
    ): SubscribeOperation<P> {
        for (subscription in sub.subscriptions) {
            publishCodecRegistry.register(subscription.topicFilter, payloadCodec)
        }
        return observeSub(processor.subscribe(sub), payloadCodec)
    }

    private fun <P : Payload> observeSub(
        subscribeRequestSent: ISubscribeRequest,
        payloadCodec: Codec<P>,
    ): SubscribeOperation<P> {
        val map =
            subscribeRequestSent.subscriptions.associateWith { subscription ->
                observe(subscription.topicFilter, payloadCodec)
            }
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
            publishCodecRegistry.unregister(topic)
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
     * thread using [bufferFactory] (defaults to the client's connection-default factory);
     * the resulting wire bytes are wrapped in an [com.ditchoom.mqtt.controlpacket.OpaquePublishPayload]
     * and queued. Constructing the [PublishMessage] directly here (rather than routing through
     * `packetFactory.publish(ReadBuffer?)`) avoids the convenience overload's extra
     * allocate-and-copy — the [eagerEncode] output's [com.ditchoom.buffer.PlatformBuffer]
     * is handed straight to [com.ditchoom.buffer.codec.opaqueBytesFrom] which takes
     * ownership.
     *
     * Routing through `Codec<P>` (rather than a raw write lambda) means the same generated
     * codec that validates spec compliance produces the wire bytes.
     */
    suspend fun <P> publish(
        topic: String,
        payload: P,
        payloadCodec: Codec<P>,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        retain: Boolean = false,
        bufferFactory: BufferFactory = this.bufferFactory,
    ): PublishResult {
        val encoded = eagerEncode(payload, payloadCodec, bufferFactory)
        val opaque =
            com.ditchoom.mqtt.controlpacket.OpaquePublishPayload(
                com.ditchoom.buffer.codec
                    .opaqueBytesFrom(encoded as com.ditchoom.buffer.PlatformBuffer),
            )
        val pub = buildPublishMessage(TopicName.fromOrThrow(topic), qos, retain, opaque)
        return publish(pub)
    }

    /**
     * Construct a version-appropriate [PublishMessage] directly carrying an
     * [com.ditchoom.mqtt.controlpacket.OpaquePublishPayload]. Skips
     * `ControlPacketFactory.publish(ReadBuffer?)`'s wrap-and-copy convenience overload —
     * the typed [publish] path has full ownership of the eagerEncoded buffer and can
     * forward it without an intermediate allocation.
     */
    private fun buildPublishMessage(
        topic: TopicName,
        qos: QualityOfService,
        retain: Boolean,
        opaque: com.ditchoom.mqtt.controlpacket.OpaquePublishPayload,
    ): PublishMessage {
        val header =
            com.ditchoom.mqtt.controlpacket.MqttFixedHeader(
                makePublishHeaderByte(dup = false, qos = qos, retain = retain),
            )
        val packetIdField =
            if (qos == QualityOfService.AT_MOST_ONCE) null else NO_PACKET_ID.toUShort()
        return when (packetFactory.protocolVersion) {
            4 ->
                com.ditchoom.mqtt3.controlpacket.PublishMessageV4(
                    header = header,
                    topicName = topic.toString(),
                    packetId = packetIdField,
                    payload = opaque,
                )
            5 ->
                com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish(
                    header = header,
                    topicName = topic.toString(),
                    packetId = packetIdField,
                    properties = emptyList(),
                    payload = opaque,
                )
            else -> error("Unsupported MQTT protocol version: ${packetFactory.protocolVersion}")
        }
    }

    /** Reproduces the v4/v5 PUBLISH fixed-header first-byte layout. */
    private fun makePublishHeaderByte(
        dup: Boolean,
        qos: QualityOfService,
        retain: Boolean,
    ): UByte {
        val dupBit = if (dup) 0x08 else 0x00
        val retainBit = if (retain) 0x01 else 0x00
        val qosBits = qos.integerValue.toInt() shl 1
        return ((3 shl 4) or dupBit or qosBits or retainBit).toUByte()
    }

    /**
     * Subscribe with a typed handler. [payloadCodec] is registered into the connection's
     * [TopicCodecRegistry] *before* the SUBSCRIBE packet leaves the wire, so the
     * dispatcher's per-PUBLISH topic-router can resolve it the moment a matching
     * message arrives. The handler receives the already-typed payload extracted from
     * the wire-decoded `PublishMessage` — no re-decode, no buffer-lifecycle contract.
     *
     * Auto-ack on handler return — if the handler throws, the message is NOT
     * acknowledged and will be redelivered on reconnect.
     */
    suspend fun <P : Payload> subscribe(
        topicFilter: String,
        payloadCodec: Codec<P>,
        maxQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        handler: suspend (PublishMessage, P) -> Unit,
    ): SubscribeOperation<P> {
        val filter = TopicFilter.fromOrThrow(topicFilter)
        publishCodecRegistry.register(filter, payloadCodec)
        val sub = packetFactory.subscribe(filter, maxQos)
        processor.publishDispatcher.subscribe(filter, SubscriberEntry(handler))
        return observeSub(processor.subscribe(sub), payloadCodec)
    }

    /**
     * Encode [value] through [codec] into a fresh read-positioned [ReadBuffer] using
     * [factory]. Picks an exact-size allocation when `codec.wireSize` returns `Exact`;
     * otherwise grows by doubling on overflow up to [MAX_BACKPATCH_BYTES] (a codec that
     * returns BackPatch but cannot fit within that cap is a configuration error —
     * callers needing larger payloads should supply a codec with an exact `wireSize`).
     *
     * Surfaces the [BufferFactory] explicitly so consumers can plug in a pool, a
     * deterministic factory, or a shared-memory allocator at the per-publish call site.
     */
    private fun <P> eagerEncode(
        value: P,
        codec: Codec<P>,
        factory: BufferFactory,
    ): ReadBuffer {
        val ctx = EncodeContext.Empty
        return when (val size = codec.wireSize(value, ctx)) {
            is WireSize.Exact -> {
                val buf = factory.allocate(size.bytes)
                codec.encode(buf, value, ctx)
                buf.resetForRead()
                buf
            }
            WireSize.BackPatch -> encodeBackPatch(value, codec, ctx, factory)
        }
    }

    private fun <P> encodeBackPatch(
        value: P,
        codec: Codec<P>,
        ctx: EncodeContext,
        factory: BufferFactory,
    ): ReadBuffer {
        var capacity = INITIAL_BACKPATCH_BYTES
        while (capacity <= MAX_BACKPATCH_BYTES) {
            val buf = factory.allocate(capacity)
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
            bufferFactory: BufferFactory = BufferFactory.Default,
            connectSingle: (suspend (MqttConnectionOptions) -> Connection<ControlPacket>)? = null,
        ): MqttClient {
            val registry = TopicCodecRegistry()
            val effectiveConnect =
                connectSingle ?: com.ditchoom.mqtt.client.net.defaultSingleConnection(
                    broker = broker,
                    publishCodecForTopic = { topic ->
                        registry.codecForTopicName(TopicName.fromOrThrow(topic))
                    },
                )
            val cm = ConnectivityManager(persistence, broker, effectiveConnect)
            val client = MqttClient(cm, scope, registry, bufferFactory)
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
