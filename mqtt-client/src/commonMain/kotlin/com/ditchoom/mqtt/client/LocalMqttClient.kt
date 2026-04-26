package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
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

class LocalMqttClient(
    internal val connectivityManager: ConnectivityManager,
    internal val scope: CoroutineScope,
) : MqttClient {
    internal val processor: ControlPacketProcessor get() = connectivityManager.processor
    override val broker: MqttBroker = connectivityManager.broker
    override val connectionState: StateFlow<ConnectionState> get() = connectivityManager.connectionState
    override val packetFactory: ControlPacketFactory = connectivityManager.broker.connectionRequest.controlPacketFactory

    private var connectionJob: Job? = null

    override suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? = connectivityManager.currentConnack()

    override suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        var c = currentConnectionAcknowledgment()
        if (c == null) {
            c = connectivityManager.connectionBroadcastChannel.take(1).first()
        }
        return c
    }

    override suspend fun pingCount() = connectivityManager.processor.pingCount

    override suspend fun pingResponseCount() = connectivityManager.processor.pingResponseCount

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

    override suspend fun publish(pub: PublishMessage): PublishResult {
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

    override fun observe(filter: TopicFilter): Flow<PublishMessage> =
        processor.readChannel.filterIsInstance<PublishMessage>().filter {
            filter.matches(it.topic)
        }

    override fun <P> observe(
        filter: TopicFilter,
        decodePayload: ReadBuffer.() -> P,
    ): Flow<Pair<PublishMessage, P>> =
        observe(filter).map { pub ->
            val raw = pub.rawPayload()
            val decoded =
                if (raw == null || raw.remaining() == 0) {
                    ReadBuffer.EMPTY_BUFFER.decodePayload()
                } else {
                    val slice = raw.slice()
                    try {
                        slice.decodePayload()
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

    override suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation = observeSub(processor.subscribe(sub))

    override suspend fun subscribe(
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

    override suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
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

    override suspend fun sendDisconnect() {
        connectivityManager.sendDisconnect()
    }

    override suspend fun shutdown(
        sendDisconnect: Boolean,
        drain: Boolean,
    ) {
        connectivityManager.shutdown(sendDisconnect, drain)
        connectionJob?.cancel()
        connectionJob = null
    }

    override suspend fun <P> publish(
        topic: String,
        payload: P,
        encodePayload: WriteBuffer.(P) -> Unit,
        qos: QualityOfService,
        retain: Boolean,
    ): PublishResult {
        val encoded = eagerEncode(payload, encodePayload)
        val pub =
            packetFactory.publish(
                topicName = TopicName.fromOrThrow(topic),
                qos = qos,
                retain = retain,
                payload = encoded,
            )
        return publish(pub)
    }

    override suspend fun <P> subscribe(
        topicFilter: String,
        decodePayload: ReadBuffer.() -> P,
        maxQos: QualityOfService,
        handler: suspend (PublishMessage, P) -> Unit,
    ): SubscribeOperation {
        val filter = TopicFilter.fromOrThrow(topicFilter)
        val sub = packetFactory.subscribe(filter, maxQos)
        processor.publishDispatcher.subscribeTyped(filter, SubscriberEntry.Typed(decodePayload, handler))
        return observeSub(processor.subscribe(sub))
    }

    private fun <P> eagerEncode(
        value: P,
        encodePayload: WriteBuffer.(P) -> Unit,
    ): ReadBuffer = com.ditchoom.buffer.codec.encodeWithGrowth { it.encodePayload(value) }

    override suspend fun pendingPublishes(): List<PublishResult> {
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

    override suspend fun connectionCount(): Long = connectivityManager.connectionCount

    override suspend fun connectionAttempts(): Long = connectivityManager.connectionAttempts

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
        ): LocalMqttClient {
            val cm = ConnectivityManager(persistence, broker, connectSingle)
            val client = LocalMqttClient(cm, scope)
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
    }
}
