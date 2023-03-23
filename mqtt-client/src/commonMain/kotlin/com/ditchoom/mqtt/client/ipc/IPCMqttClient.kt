package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.mqtt.Persistence
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
import kotlinx.coroutines.flow.first

class IPCMqttClient(
    private val scope: CoroutineScope,
    private val broker: MqttBroker,
    private val persistence: Persistence,
    private val emitMessage: suspend (IPCMqttMessage) -> Unit
) {
    private val incomingPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sentPackets = MutableSharedFlow<ControlPacket>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun subscribe(sub: ISubscribeRequest) = scope.async {
        val subscribe = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        val suback = async {
            val packet =
                awaitControlPacketReceivedMatching(
                    subscribe.packetIdentifier,
                    ISubscribeAcknowledgement.controlPacketValue
                )
            packet as ISubscribeAcknowledgement
        }
        emitMessage(
            IPCMqttMessage.SendControlPacket(
                broker.identifier,
                subscribe.controlPacketFactory.protocolVersion,
                subscribe.controlPacketValue,
                subscribe.packetIdentifier
            )
        )
        SubscribeOperation(subscribe.packetIdentifier, suback)
    }

    fun publish(pub: IPublishMessage) = scope.async {
        val qos0Pub = if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            pub.serialize(AllocationZone.AndroidSharedMemory)
        } else {
            null
        }
        val publishPacketId = if (qos0Pub != null) {
            NO_PACKET_ID
        } else {
            persistence.writePubGetPacketId(broker, pub)
        }
        emitMessage(
            IPCMqttMessage.SendControlPacket(
                broker.identifier,
                pub.controlPacketFactory.protocolVersion,
                pub.controlPacketValue,
                publishPacketId,
                qos0Pub
            )
        )
        when (pub.qualityOfService) {
            QualityOfService.AT_MOST_ONCE -> PublishOperation.QoSAtMostOnceComplete
            QualityOfService.AT_LEAST_ONCE -> {
                val puback = async {
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishAcknowledgment.controlPacketValue)
                        as IPublishAcknowledgment
                }
                PublishOperation.QoSAtLeastOnce(publishPacketId, puback)
            }

            QualityOfService.EXACTLY_ONCE -> {
                val pubrec = async {
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishAcknowledgment.controlPacketValue)
                        as IPublishReceived
                }
                val pubcomp = async {
                    pubrec.await()
                    awaitControlPacketReceivedMatching(publishPacketId, IPublishAcknowledgment.controlPacketValue)
                        as IPublishComplete
                }
                PublishOperation.QoSExactlyOnce(publishPacketId, pubrec, pubcomp)
            }
        }
    }

    fun unsubscribe(unsub: IUnsubscribeRequest) = scope.async {
        val packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        val unsuback = async {
            val packet =
                awaitControlPacketReceivedMatching(packetId, IUnsubscribeAcknowledgment.controlPacketValue)
            packet as IUnsubscribeAcknowledgment
        }
        emitMessage(
            IPCMqttMessage.SendControlPacket(
                broker.identifier,
                unsub.controlPacketFactory.protocolVersion,
                unsub.controlPacketValue,
                packetId
            )
        )
        UnsubscribeOperation(packetId, unsuback)
    }

    private suspend fun awaitControlPacketReceivedMatching(packetId: Int, controlPacketValue: Byte): ControlPacket {
        return incomingPackets.first { it.packetIdentifier == packetId && it.controlPacketValue == controlPacketValue }
    }
    internal suspend fun onLog(log: String) {
    }
    internal suspend fun onIncomingControlPacket(c: ControlPacket) {
        incomingPackets.emit(c)
    }

    internal suspend fun onControlPacketSent(c: ControlPacket) {
        sentPackets.emit(c)
    }
}
