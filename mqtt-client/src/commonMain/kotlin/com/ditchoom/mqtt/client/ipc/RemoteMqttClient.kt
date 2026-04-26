package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.ConnectionState
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.PublishResult
import com.ditchoom.mqtt.client.QoS1State
import com.ditchoom.mqtt.client.QoS2State
import com.ditchoom.mqtt.client.SubscribeOperation
import com.ditchoom.mqtt.client.SubscriptionHandler
import com.ditchoom.mqtt.client.UnsubscribeOperation
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class RemoteMqttClient(
    protected val scope: CoroutineScope,
    override val broker: MqttBroker,
    private val persistence: Persistence,
) : MqttClient {
    abstract val bufferFactory: BufferFactory
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState
    private val _incomingPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incomingPackets: SharedFlow<ControlPacket> = _incomingPackets
    private val _sentPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sentPackets: SharedFlow<ControlPacket> = _sentPackets

    override suspend fun pendingPublishes(): List<PublishResult> = emptyList()

    protected open suspend fun sendSubscribe(packetId: Int) {}

    override suspend fun subscribe(
        sub: ISubscribeRequest,
        handler: SubscriptionHandler,
    ): SubscribeOperation = subscribe(sub) // handler-based dispatch not supported across IPC boundary

    override suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation {
        val subscribe = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        val suback =
            scope.async {
                val packet =
                    awaitControlPacketReceivedMatching(
                        subscribe.packetIdentifier,
                        ISubscribeAcknowledgement.CONTROL_PACKET_VALUE,
                    )
                packet as ISubscribeAcknowledgement
            }
        sendSubscribe(subscribe.packetIdentifier)
        val map = subscribe.subscriptions.associateWith { observe(it.topicFilter) }
        return SubscribeOperation(subscribe.packetIdentifier, map, suback)
    }

    protected open suspend fun sendPublish(
        packetId: Int,
        pubBuffer: ReadBuffer,
    ) {}

    override suspend fun publish(pub: PublishMessage): PublishResult {
        val publishPacketId =
            if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
                NO_PACKET_ID
            } else {
                persistence.writePubGetPacketId(broker, pub)
            }
        val pub = pub.maybeCopyWithNewPacketIdentifier(publishPacketId)
        val pubBuffer = pub.serialize(bufferFactory)
        sendPublish(publishPacketId, pubBuffer)
        return when (pub.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> PublishResult.QoS0Sent
            QualityOfService.AT_LEAST_ONCE -> {
                val stateFlow = MutableStateFlow<QoS1State>(QoS1State.Queued)
                scope.launch {
                    val ack =
                        awaitControlPacketReceivedMatching(publishPacketId, IPublishAcknowledgment.CONTROL_PACKET_VALUE)
                            as IPublishAcknowledgment
                    stateFlow.value = QoS1State.Acknowledged(ack)
                }
                PublishResult.QoS1(publishPacketId, stateFlow)
            }

            QualityOfService.EXACTLY_ONCE -> {
                val stateFlow = MutableStateFlow<QoS2State>(QoS2State.Queued)
                scope.launch {
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishReceived.CONTROL_PACKET_VALUE)
                    stateFlow.value = QoS2State.Received
                    val comp =
                        awaitControlPacketReceivedMatching(publishPacketId, IPublishComplete.CONTROL_PACKET_VALUE)
                            as IPublishComplete
                    stateFlow.value = QoS2State.Complete(comp)
                }
                PublishResult.QoS2(publishPacketId, stateFlow)
            }
        }
    }

    protected open suspend fun sendUnsubscribe(packetId: Int) = Unit

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
                topicName =
                    com.ditchoom.mqtt.controlpacket.TopicName
                        .fromOrThrow(topic),
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
        // Typed subscribe across IPC: the IPC boundary operates on ReadBuffer-typed publishes;
        // typed decode happens on the collector side. Since the untyped subscribe returns a
        // SubscribeOperation with per-subscription Flow<PublishMessage>, callers can map each
        // incoming message through [decodePayload] themselves. Until worker-side handler dispatch
        // lands (Phase 5), this is a thin wrapper over the raw subscribe path.
        return subscribe(
            packetFactory.subscribe(
                com.ditchoom.mqtt.controlpacket.TopicFilter
                    .fromOrThrow(topicFilter),
                maxQos,
            ),
        )
    }

    override fun <P> observe(
        filter: com.ditchoom.mqtt.controlpacket.TopicFilter,
        decodePayload: ReadBuffer.() -> P,
    ): kotlinx.coroutines.flow.Flow<Pair<PublishMessage, P>> =
        kotlinx.coroutines.flow.flow {
            observe(filter).collect { pub ->
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
                emit(pub to decoded)
            }
        }

    private fun <P> eagerEncode(
        value: P,
        encodePayload: WriteBuffer.(P) -> Unit,
    ): ReadBuffer {
        val encoder =
            object : Encoder<P> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: P,
                ) {
                    buffer.encodePayload(value)
                }
            }
        return encoder.encodeToBuffer(value)
    }

    override suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
        val packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        val unsuback =
            scope.async {
                val packet =
                    awaitControlPacketReceivedMatching(packetId, IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE)
                packet as IUnsubscribeAcknowledgment
            }
        sendUnsubscribe(packetId)
        return UnsubscribeOperation(packetId, unsuback)
    }

    private suspend fun awaitControlPacketReceivedMatching(
        packetId: Int,
        controlPacketValue: Byte,
    ): ControlPacket =
        _incomingPackets.first {
            it.packetIdentifier == packetId && it.controlPacketValue == controlPacketValue
        }

    protected fun onIncomingControlPacket(c: ControlPacket) {
        scope.launch { _incomingPackets.emit(c) }
    }

    protected fun onControlPacketSent(c: ControlPacket) {
        scope.launch { _sentPackets.emit(c) }
    }
}
