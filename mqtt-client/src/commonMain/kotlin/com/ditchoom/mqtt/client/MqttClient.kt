package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.codec.IdentityBufferCodec
import com.ditchoom.mqtt.codec.PayloadCodec
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MqttClient {
    val packetFactory: ControlPacketFactory
    val broker: MqttBroker

    /** Observable connection state. Collect to track Connected/Disconnected/Reconnecting/Failed. */
    val connectionState: StateFlow<ConnectionState>

    suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment?

    suspend fun awaitConnectivity(): IConnectionAcknowledgment

    suspend fun pingCount(): Long

    suspend fun pingResponseCount(): Long

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

    suspend fun publish(pub: PublishMessage): PublishResult

    /**
     * Observe incoming publishes matching [filter] as an untyped flow. Payloads are the raw
     * wire [PublishMessage] instances (each carries its own owned payload).
     */
    fun observe(filter: TopicFilter): Flow<PublishMessage>

    /**
     * Observe incoming publishes matching [filter] decoded through [codec]. Each emitted
     * value contains the owned typed payload — no buffer-lifecycle contract.
     */
    fun <P> observe(
        filter: TopicFilter,
        codec: PayloadCodec<P>,
    ): Flow<Pair<PublishMessage, P>>

    suspend fun subscribe(
        topicFilter: String,
        maxQos: QualityOfService,
    ): SubscribeOperation = subscribe(packetFactory.subscribe(TopicFilter.fromOrThrow(topicFilter), maxQos))

    suspend fun subscribe(subscriptions: Set<ISubscription>): SubscribeOperation =
        subscribe(
            packetFactory.subscribe(subscriptions),
        )

    suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation

    /**
     * Subscribe with a callback handler for incoming publishes.
     *
     * The handler receives a [PublishMessage] whose payload is owned (no scope contract);
     * capture it freely. For typed payloads, prefer the overload that accepts a
     * [PayloadCodec].
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

    /**
     * Subscribe with a callback handler for incoming publishes.
     *
     * @see subscribe(String, QualityOfService, SubscriptionHandler)
     */
    suspend fun subscribe(
        sub: ISubscribeRequest,
        handler: SubscriptionHandler,
    ): SubscribeOperation

    // --- v2 typed API ---

    /**
     * Publish a typed payload. The [codec] writes [payload] directly into the wire buffer
     * (zero intermediate copy if the codec implementation doesn't allocate internally).
     */
    suspend fun <P> publish(
        topic: String,
        payload: P,
        codec: PayloadCodec<P>,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        retain: Boolean = false,
    ): PublishResult

    /**
     * Publish raw bytes. Convenience over [publish] with [IdentityBufferCodec].
     */
    suspend fun publish(
        topic: String,
        payload: ReadBuffer,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        retain: Boolean = false,
    ): PublishResult = publish(topic, payload, IdentityBufferCodec, qos, retain)

    /**
     * Subscribe with a typed handler. The dispatcher decodes the payload via [codec] and
     * passes both the [PublishMessage] (for metadata) and the decoded value to [handler].
     * Auto-ack on handler return — if the handler throws, the message is NOT acknowledged
     * and will be redelivered on reconnect.
     */
    suspend fun <P> subscribe(
        topicFilter: String,
        codec: PayloadCodec<P>,
        maxQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        handler: suspend (PublishMessage, P) -> Unit,
    ): SubscribeOperation

    // --- unsubscribe ---

    suspend fun unsubscribe(topicFilter: String): UnsubscribeOperation =
        unsubscribe(packetFactory.unsubscribe(TopicFilter.fromOrThrow(topicFilter)))

    suspend fun unsubscribe(subscriptions: Set<TopicFilter>): UnsubscribeOperation =
        unsubscribe(
            packetFactory.unsubscribe(subscriptions),
        )

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation

    suspend fun sendDisconnect()

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
    )

    /**
     * Returns in-flight publish operations that survived a process restart.
     * Each has a [PublishResult] with a [StateFlow] reconstructed from the persisted state.
     * Useful for recovering UI state or tracking delivery after restart.
     */
    suspend fun pendingPublishes(): List<PublishResult>

    suspend fun connectionCount(): Long

    suspend fun connectionAttempts(): Long
}
