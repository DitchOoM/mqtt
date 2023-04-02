package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
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

class LocalMqttClient(
    internal val connectivityManager: ConnectivityManager
) : MqttClient {
    internal val scope: CoroutineScope = connectivityManager.scope
    internal val processor: ControlPacketProcessor = connectivityManager.processor
    override val broker: MqttBroker = connectivityManager.broker
    var observer: Observer? = null
        set(value) {
            connectivityManager.observer = value
            processor.observer = value
            field = value
        }
    var allocateSharedMemory: Boolean = connectivityManager.allocateSharedMemory
        set(value) {
            field = value
            connectivityManager.allocateSharedMemory = value
        }
        get() {
            return connectivityManager.allocateSharedMemory
        }
    override val packetFactory: ControlPacketFactory = connectivityManager.broker.connectionRequest.controlPacketFactory

    override suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? =
        connectivityManager.currentConnack()

    override suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        var c = currentConnectionAcknowledgment()
        if (c == null) {
            c = connectivityManager.connectionBroadcastChannel.take(1).first()
        }
        return c
    }

    override suspend fun pingCount() = connectivityManager.processor.pingCount
    override suspend fun pingResponseCount() = connectivityManager.processor.pingResponseCount

    suspend fun sendQueuedPublishMessage(packetId: Int, pubQos0: IPublishMessage?) {
        val pub = if (pubQos0 != null && pubQos0.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            pubQos0
        } else {
            connectivityManager.persistence.getPubWithPacketId(
                connectivityManager.broker, packetId
            )
        } ?: return
        processor.publish(pub, false)
    }

    override suspend fun publish(pub: IPublishMessage): PublishOperation {
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

    override fun observe(filter: Topic): Flow<IPublishMessage> {
        return processor.readChannel.filterIsInstance<IPublishMessage>().filter {
            filter.matches(it.topic)
        }
    }

    suspend fun sendQueuedSubscribeMessage(packetId: Int) {
        val sub =
            connectivityManager.persistence.getSubWithPacketId(connectivityManager.broker, packetId) ?: return
        processor.subscribe(sub, false)
    }

    override suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation = observeSub(processor.subscribe(sub))

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

    suspend fun sendQueuedUnsubscribeMessage(packetId: Int) {
        val unsub =
            connectivityManager.persistence.getUnsubWithPacketId(connectivityManager.broker, packetId) ?: return
        processor.unsubscribe(unsub, false)
    }

    override suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
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

    override suspend fun sendDisconnect() {
        connectivityManager.sendDisconnect()
    }

    override suspend fun shutdown(sendDisconnect: Boolean) {
        connectivityManager.shutdown(sendDisconnect)
    }

    override suspend fun connectionCount(): Long = connectivityManager.connectionCount
    override suspend fun connectionAttempts(): Long = connectivityManager.connectionAttempts

    companion object {

        fun stayConnected(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Stay Connected")),
            broker: MqttBroker,
            persistence: Persistence,
            allocateSharedMemoryInitial: Boolean = false,
            observer: Observer? = null,
            sentMessage: (ReadBuffer) -> Unit = {},
            incomingMessage: (UByte, Int, ReadBuffer) -> Unit = { _, _, _ -> }
        ): LocalMqttClient {
            val connectivityManager = ConnectivityManager(
                scope,
                persistence,
                broker,
                allocateSharedMemoryInitial,
                sentMessage,
                incomingMessage
            )
            val c = LocalMqttClient(connectivityManager)
            c.observer = observer
            connectivityManager.stayConnected()
            return c
        }

        suspend fun connectOnce(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Connect Once")),
            broker: MqttBroker,
            persistence: Persistence,
            allocateSharedMemoryInitial: Boolean = false,
            observer: Observer? = null,
            sentMessage: (ReadBuffer) -> Unit = {},
            incomingMessage: (UByte, Int, ReadBuffer) -> Unit = { _, _, _ -> }
        ): LocalMqttClient {
            val connectivityManager = ConnectivityManager(
                scope,
                persistence,
                broker,
                allocateSharedMemoryInitial,
                sentMessage,
                incomingMessage
            )
            val c = LocalMqttClient(connectivityManager)
            c.observer = observer
            connectivityManager.connectOnce()
            return c
        }
    }
}
