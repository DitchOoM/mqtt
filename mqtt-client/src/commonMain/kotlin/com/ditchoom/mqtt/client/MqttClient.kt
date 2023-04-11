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
import com.ditchoom.mqtt.controlpacket.Topic
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
        retain: Boolean = false
    ): PublishOperation = publish(
        packetFactory.publish(
            topicName = Topic.fromOrThrow(topicName, Topic.Type.Name),
            qos = qos,
            retain = retain,
            payload = payload
        )
    )

    suspend fun publish(pub: IPublishMessage): PublishOperation

    fun observe(filter: Topic): Flow<IPublishMessage>

    suspend fun subscribe(topicFilter: String, maxQos: QualityOfService): SubscribeOperation =
        subscribe(packetFactory.subscribe(Topic.fromOrThrow(topicFilter, Topic.Type.Filter), maxQos))

    suspend fun subscribe(subscriptions: Set<ISubscription>): SubscribeOperation = subscribe(
        packetFactory.subscribe(subscriptions)
    )

    suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation

    suspend fun unsubscribe(topicFilter: String): UnsubscribeOperation =
        unsubscribe(packetFactory.unsubscribe(Topic.fromOrThrow(topicFilter, Topic.Type.Filter)))

    suspend fun unsubscribe(subscriptions: Set<Topic>): UnsubscribeOperation = unsubscribe(
        packetFactory.unsubscribe(subscriptions)
    )

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation

    suspend fun sendDisconnect()

    suspend fun shutdown(sendDisconnect: Boolean = true)

    suspend fun connectionCount(): Long

    suspend fun connectionAttempts(): Long
}
