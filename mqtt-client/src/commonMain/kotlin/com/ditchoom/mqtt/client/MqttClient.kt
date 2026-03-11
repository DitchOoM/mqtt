package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlinx.coroutines.flow.Flow

interface MqttClient {
    val packetFactory: ControlPacketFactory
    val broker: MqttBroker

    suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment?

    suspend fun awaitConnectivity(): IConnectionAcknowledgment

    suspend fun pingCount(): Long

    suspend fun pingResponseCount(): Long

    suspend fun publish(
        topicName: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        payload: ReadBuffer? = null,
        retain: Boolean = false,
    ): PublishOperation =
        publish(
            packetFactory.publish(
                topicName = TopicName.fromOrThrow(topicName),
                qos = qos,
                retain = retain,
                payload = payload,
            ),
        )

    suspend fun publish(pub: IPublishMessage): PublishOperation

    fun observe(filter: TopicFilter): Flow<IPublishMessage>

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
     * The handler receives [com.ditchoom.mqtt.controlpacket.IncomingPublish] which can be
     * smart-cast to [com.ditchoom.mqtt.controlpacket.IncomingPublishV5] for v5 properties.
     *
     * The payload buffer is scoped — it is only valid during the handler invocation.
     * Copy the bytes if you need them beyond the callback.
     *
     * @param topicFilter The topic filter to subscribe to
     * @param maxQos Maximum QoS for the subscription
     * @param handler Callback invoked for each matching incoming publish
     * @return The subscribe operation with the SUBACK deferred
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

    suspend fun unsubscribe(topicFilter: String): UnsubscribeOperation =
        unsubscribe(packetFactory.unsubscribe(TopicFilter.fromOrThrow(topicFilter)))

    suspend fun unsubscribe(subscriptions: Set<TopicFilter>): UnsubscribeOperation =
        unsubscribe(
            packetFactory.unsubscribe(subscriptions),
        )

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation

    suspend fun sendDisconnect()

    suspend fun shutdown(sendDisconnect: Boolean = true)

    suspend fun connectionCount(): Long

    suspend fun connectionAttempts(): Long
}
