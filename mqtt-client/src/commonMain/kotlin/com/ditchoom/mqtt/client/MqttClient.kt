package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
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
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take

class MqttClient(internal val connectivityManager: ConnectivityManager) {
    private val scope: CoroutineScope = connectivityManager.scope
    internal val processor: ControlPacketProcessor = connectivityManager.processor
    var observer: Observer? = null
        set(value) {
            connectivityManager.observer = value
            processor.observer = value
            field = value
        }

    var incomingMessage: (ControlPacket) -> Unit
        set(value) {
            connectivityManager.incomingMessage = value
        }
        get() = connectivityManager.incomingMessage

    var sentMessage: (Collection<ControlPacket>) -> Unit
        set(value) {
            connectivityManager.sentMessage = value
        }
        get() = connectivityManager.sentMessage

    fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? = connectivityManager.currentConnack()

    suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        var c = currentConnectionAcknowledgment()
        println("await connectivity $c")
        if (c == null) {
            c = connectivityManager.connectionBroadcastChannel.take(1).first()
        }
        return c
    }

    fun controlPacketFactory() = connectivityManager.broker.connectionRequest.controlPacketFactory
    fun pingCount() = connectivityManager.processor.pingCount
    fun pingResponseCount() = connectivityManager.processor.pingResponseCount

    suspend fun sendQueuedPublishMessage(packetId: Int, pubQos0: IPublishMessage?): PublishOperation? {
        val pub = if (pubQos0 != null && pubQos0.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            pubQos0
        } else {
            connectivityManager.persistence.getPubWithPacketId(
                connectivityManager.broker, packetId
            )
        } ?: return null
        return observePub(processor.publish(pub, false))
    }

    suspend fun publish(pub: IPublishMessage): PublishOperation {
        return observePub(processor.publish(pub))
    }

    private fun observePub(publishMessage: IPublishMessage): PublishOperation {
        return when (publishMessage.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> {
                PublishOperation.QoSAtMostOnceComplete
            }

            QualityOfService.AT_LEAST_ONCE -> {
                check(publishMessage.packetIdentifier != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val packetId = publishMessage.packetIdentifier
                val pubAck = scope.async {
                    processor.awaitIncomingPacketId<IPublishAcknowledgment>(
                        packetId,
                        IPublishAcknowledgment.controlPacketValue
                    )
                }
                PublishOperation.QoSAtLeastOnce(packetId, pubAck)
            }

            QualityOfService.EXACTLY_ONCE -> {
                val packetId = publishMessage.packetIdentifier
                check(publishMessage.packetIdentifier != NO_PACKET_ID) { "PacketId must be set by the persistence" }
                val pubRecReceived = scope.async {
                    processor.awaitIncomingPacketId(
                        packetId,
                        IPublishReceived.controlPacketValue
                    ) as IPublishReceived
                }
                val pubCompReceived = scope.async {
                    pubRecReceived.await()
                    processor.awaitIncomingPacketId<IPublishComplete>(packetId, IPublishComplete.controlPacketValue)
                }
                PublishOperation.QoSExactlyOnce(packetId, pubRecReceived, pubCompReceived)
            }
        }
    }

    fun observe(filter: Topic): Flow<IPublishMessage> {
        return processor.readChannel.filterIsInstance<IPublishMessage>().filter {
            filter.matches(it.topic)
        }
    }

    suspend fun sendQueuedSubscribeMessage(packetId: Int): SubscribeOperation? {
        val sub =
            connectivityManager.persistence.getSubWithPacketId(connectivityManager.broker, packetId) ?: return null
        return observeSub(processor.subscribe(sub, false))
    }

    suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation = observeSub(processor.subscribe(sub))

    private fun observeSub(subscribeRequestSent: ISubscribeRequest): SubscribeOperation {
        return SubscribeOperation(
            subscribeRequestSent.packetIdentifier,
            scope.async {
                processor.awaitIncomingPacketId(
                    subscribeRequestSent.packetIdentifier,
                    ISubscribeAcknowledgement.controlPacketValue
                ) as ISubscribeAcknowledgement
            }
        )
    }

    suspend fun sendQueuedUnsubscribeMessage(packetId: Int): UnsubscribeOperation? {
        val unsub =
            connectivityManager.persistence.getUnsubWithPacketId(connectivityManager.broker, packetId) ?: return null
        return observeUnsubscribe(unsub)
    }

    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
        return observeUnsubscribe(processor.unsubscribe(unsub))
    }

    private fun observeUnsubscribe(unsubscribeRequestSent: IUnsubscribeRequest): UnsubscribeOperation {
        return UnsubscribeOperation(
            unsubscribeRequestSent.packetIdentifier,
            scope.async {
                processor.awaitIncomingPacketId(
                    unsubscribeRequestSent.packetIdentifier,
                    IUnsubscribeAcknowledgment.controlPacketValue
                ) as IUnsubscribeAcknowledgment
            }
        )
    }

    suspend fun sendDisconnect() {
        connectivityManager.sendDisconnect()
    }

    suspend fun shutdown(sendDisconnect: Boolean = true) {
        connectivityManager.shutdown(sendDisconnect)
    }

    fun connectionCount(): Long = connectivityManager.connectionCount
    fun connectionAttempts(): Long = connectivityManager.connectionAttempts

    companion object {

        fun stayConnected(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Stay Connected")),
            broker: MqttBroker,
            persistence: Persistence,
            observer: Observer? = null,
        ): MqttClient {
            val connectivityManager = ConnectivityManager(scope, persistence, broker)
            val c = MqttClient(connectivityManager)
            c.observer = observer
            connectivityManager.stayConnected()
            return c
        }

        suspend fun connectOnce(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Connect Once")),
            broker: MqttBroker,
            persistence: Persistence,
            observer: Observer? = null,
        ): MqttClient {
            val connectivityManager = ConnectivityManager(scope, persistence, broker)
            val c = MqttClient(connectivityManager)
            c.observer = observer
            connectivityManager.connectOnce()
            return c
        }
    }
}
