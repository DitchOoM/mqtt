package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.flow.Flow

interface MqttClient {
    val packetFactory: ControlPacketFactory
    val broker: MqttBroker

    suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment?

    suspend fun awaitConnectivity(): IConnectionAcknowledgment

    suspend fun pingCount(): Long

    suspend fun pingResponseCount(): Long

    suspend fun publish(pub: IPublishMessage): PublishOperation

    fun observe(filter: Topic): Flow<IPublishMessage>

    suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation

    suspend fun sendDisconnect()

    suspend fun shutdown(sendDisconnect: Boolean = true)

    suspend fun connectionCount(): Long

    suspend fun connectionAttempts(): Long
}
