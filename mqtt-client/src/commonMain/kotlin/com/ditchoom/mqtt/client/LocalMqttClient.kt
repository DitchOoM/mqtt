package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.buffer.flow.Connection
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

class LocalMqttClient(
    internal val connectivityManager: ConnectivityManager,
    internal val scope: CoroutineScope,
) : MqttClient {
    internal val processor: ControlPacketProcessor get() = connectivityManager.processor
    override val broker: MqttBroker = connectivityManager.broker
    override val connectionState: StateFlow<ConnectionState> get() = connectivityManager.connectionState
    var observer: Observer? = null
        set(value) {
            connectivityManager.observer = value
            field = value
        }
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
        pubQos0: IPublishMessage?,
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

    override suspend fun publish(pub: IPublishMessage): PublishResult {
        val prepared = processor.preparePublish(pub)
        val result = observePub(prepared)
        processor.sendPacket(prepared)
        return result
    }

    private fun observePub(publishMessage: IPublishMessage): PublishResult =
        when (publishMessage.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> PublishResult.QoS0Sent

            QualityOfService.AT_LEAST_ONCE -> {
                check(publishMessage.packetIdentifier != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val packetId = publishMessage.packetIdentifier
                val stateFlow = MutableStateFlow<QoS1State>(QoS1State.Queued)
                scope.launch {
                    val ack = processor.awaitIncomingPacketId<IPublishAcknowledgment>(
                        packetId,
                        IPublishAcknowledgment.CONTROL_PACKET_VALUE,
                    )
                    stateFlow.value = QoS1State.Acknowledged(ack)
                }
                PublishResult.QoS1(packetId, stateFlow)
            }

            QualityOfService.EXACTLY_ONCE -> {
                val packetId = publishMessage.packetIdentifier
                check(publishMessage.packetIdentifier != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val stateFlow = MutableStateFlow<QoS2State>(QoS2State.Queued)
                scope.launch {
                    processor.awaitIncomingPacketId<IPublishReceived>(
                        packetId,
                        IPublishReceived.CONTROL_PACKET_VALUE,
                    )
                    stateFlow.value = QoS2State.Received
                    processor.awaitIncomingPacketId<IPublishComplete>(
                        packetId,
                        IPublishComplete.CONTROL_PACKET_VALUE,
                    )
                    stateFlow.value = QoS2State.Complete(
                        processor.awaitIncomingPacketId(packetId, IPublishComplete.CONTROL_PACKET_VALUE),
                    )
                }
                PublishResult.QoS2(packetId, stateFlow)
            }
        }

    override fun observe(filter: TopicFilter): Flow<IPublishMessage> =
        processor.readChannel.filterIsInstance<IPublishMessage>().filter {
            filter.matches(it.topic)
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
        qos: QualityOfService,
        retain: Boolean,
        encoder: PayloadEncoder<P>,
    ): PublishResult {
        val pub = packetFactory.publish(
            topicName = TopicName.fromOrThrow(topic),
            qos = qos,
            retain = retain,
            payload = null, // payload encoded via backpatching in serializeToSlice
        )
        // TODO: integrate encoder into serializeToSlice path for zero-copy
        return publish(pub)
    }

    override suspend fun <P> subscribe(
        topicFilter: String,
        maxQos: QualityOfService,
        decoder: PayloadDecoder<P>,
    ): MqttSubscription<P> = subscribeTypedInternal(topicFilter, maxQos, decoder, handler = null)

    override suspend fun <P> subscribe(
        topicFilter: String,
        maxQos: QualityOfService,
        decoder: PayloadDecoder<P>,
        handler: suspend (P) -> Unit,
    ): MqttSubscription<P> = subscribeTypedInternal(topicFilter, maxQos, decoder, handler)

    private suspend fun <P> subscribeTypedInternal(
        topicFilter: String,
        maxQos: QualityOfService,
        decoder: PayloadDecoder<P>,
        handler: (suspend (P) -> Unit)?,
    ): MqttSubscription<P> {
        val filter = TopicFilter.fromOrThrow(topicFilter)
        val sub = packetFactory.subscribe(filter, maxQos)
        val flow = processor.publishDispatcher.subscribeTyped(filter, decoder, handler)
        val subOp = processor.subscribe(sub)
        val subAck = scope.async {
            processor.awaitIncomingPacketId<ISubscribeAcknowledgement>(
                subOp.packetIdentifier,
                ISubscribeAcknowledgement.CONTROL_PACKET_VALUE,
            )
        }
        return object : MqttSubscription<P> {
            override val topicFilter: String = topicFilter
            override val suback = subAck
            override fun receive() = flow
            override suspend fun unsubscribe() = scope.async {
                val unsub = packetFactory.unsubscribe(filter)
                val op = this@LocalMqttClient.unsubscribe(unsub)
                processor.publishDispatcher.unsubscribe(filter)
                op.unsubAck.await()
            }
        }
    }

    internal fun isStopped() = connectionJob?.isActive != true

    override suspend fun connectionCount(): Long = connectivityManager.connectionCount

    override suspend fun connectionAttempts(): Long = connectivityManager.connectionAttempts

    companion object {
        /**
         * Creates a client and starts the connection. If the caller wants reconnection,
         * wrap the [connect] factory with a reconnecting connection before passing it in.
         */
        fun start(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Client")),
            broker: MqttBroker,
            persistence: Persistence,
            connect: suspend () -> Connection<ControlPacket>,
            observer: Observer? = null,
        ): LocalMqttClient {
            val cm = ConnectivityManager(persistence, broker, connect)
            val client = LocalMqttClient(cm, scope)
            client.observer = observer
            client.connectionJob = scope.launch { cm.run() }
            return client
        }
    }
}
