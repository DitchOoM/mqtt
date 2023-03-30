package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.PublishOperation
import com.ditchoom.mqtt.client.SubscribeOperation
import com.ditchoom.mqtt.client.UnsubscribeOperation
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class RemoteMqttClient(
    protected val scope: CoroutineScope,
    override val broker: MqttBroker,
    private val persistence: Persistence
) : MqttClient {
    private val _incomingPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incomingPackets: SharedFlow<ControlPacket> = _incomingPackets
    private val _sentPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sendPackets: SharedFlow<ControlPacket> = _sentPackets
    protected open suspend fun sendSubscribe(packetId: Int) {}

    override suspend fun subscribe(sub: ISubscribeRequest): SubscribeOperation {
        val subscribe = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        val suback = scope.async {
            val packet =
                awaitControlPacketReceivedMatching(
                    subscribe.packetIdentifier,
                    ISubscribeAcknowledgement.controlPacketValue
                )
            packet as ISubscribeAcknowledgement
        }
        sendSubscribe(subscribe.packetIdentifier)
        return SubscribeOperation(subscribe.packetIdentifier, suback)
    }

    protected open suspend fun sendPublish(packetId: Int, pubBuffer: PlatformBuffer) {}

    override suspend fun publish(pub: IPublishMessage): PublishOperation {
        val pubBuffer = pub.serialize(AllocationZone.SharedMemory)
        val publishPacketId = if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            NO_PACKET_ID
        } else {
            persistence.writePubGetPacketId(broker, pub)
        }
        sendPublish(publishPacketId, pubBuffer)
        return when (pub.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> PublishOperation.QoSAtMostOnceComplete
            QualityOfService.AT_LEAST_ONCE -> {
                val puback = scope.async {
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishAcknowledgment.controlPacketValue)
                        as IPublishAcknowledgment
                }
                PublishOperation.QoSAtLeastOnce(publishPacketId, puback)
            }

            QualityOfService.EXACTLY_ONCE -> {
                val pubrec = scope.async {
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishReceived.controlPacketValue)
                        as IPublishReceived
                }
                val pubcomp = scope.async {
                    pubrec.await()
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishComplete.controlPacketValue)
                        as IPublishComplete
                }
                PublishOperation.QoSExactlyOnce(publishPacketId, pubrec, pubcomp)
            }
        }
    }

    protected open suspend fun sendUnsubscribe(packetId: Int) = Unit

    override suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation {
        val packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        val unsuback = scope.async {
            val packet =
                awaitControlPacketReceivedMatching(packetId, IUnsubscribeAcknowledgment.controlPacketValue)
            packet as IUnsubscribeAcknowledgment
        }
        sendUnsubscribe(packetId)
        return UnsubscribeOperation(packetId, unsuback)
    }

    private suspend fun awaitControlPacketReceivedMatching(packetId: Int, controlPacketValue: Byte): ControlPacket {
        return _incomingPackets.first {
            it.packetIdentifier == packetId && it.controlPacketValue == controlPacketValue
        }
    }

    protected fun onIncomingControlPacket(c: ControlPacket) {
        scope.launch { _incomingPackets.emit(c) }
    }

    protected fun onControlPacketSent(c: ControlPacket) {
        scope.launch { _sentPackets.emit(c) }
    }
}
