package com.ditchoom.mqtt.serviceworker

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.Observer
import com.ditchoom.mqtt.client.PublishOperation
import com.ditchoom.mqtt.client.SubscribeOperation
import com.ditchoom.mqtt.client.UnsubscribeOperation
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.w3c.dom.BroadcastChannel
import web.serviceworker.ServiceWorker

class IpcMqttAppClient(
    private val broker: MqttBroker,
    private val serviceWorker: ServiceWorker,
    private val factory: ControlPacketFactory,
    private val persistence: Persistence
) {
    init {
        console.log("alloc ipc mqtt app client")
    }

    private var producerScope: ProducerScope<*>? = null
    private val broadcastChannel =
        BroadcastChannel(broadcastChannelName(broker.identifier, factory.protocolVersion.toByte()))
    val messageFlow = callbackFlow {
        producerScope = this
        console.log("alloc bc app client", broadcastChannel)
        broadcastChannel.onmessage = { event ->
            val msg = MqttServiceWorkerMessage.from(event.data)

            console.log("incoming broadcast channel $msg ", event)
            if (msg != null) {
                when (msg) {
                    is MqttServiceWorkerMessage.Log -> {
                        val o = observer
                        if (o != null) {
                            ipcLogger.forwardMessageEvent(event, o)
                        }
                    }

                    else -> {
                        trySend(msg)
                    }
                }
            }
        }
        awaitClose {
            broadcastChannel.close()
        }
    }
    private val ipcLogger = IpcObserverClient(broker)
    var observer: Observer? = null

    fun controlPacketFactory(): ControlPacketFactory = factory

    fun subscribe(sub: ISubscribeRequest) = GlobalScope.promise {
        val subscribe = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        val suback = async {
            val packet =
                awaitControlPacketReceivedMatching(
                    subscribe.packetIdentifier,
                    ISubscribeAcknowledgement.controlPacketValue
                )
            packet as ISubscribeAcknowledgement
        }
        serviceWorker.postMessage(
            MqttServiceWorkerMessage.SendControlPacket(
                broker.identifier,
                factory.protocolVersion,
                subscribe.controlPacketValue,
                subscribe.packetIdentifier
            )
        )
        SubscribeOperation(subscribe.packetIdentifier, suback)
    }

    suspend fun awaitControlPacketReceivedMatching(packetId: Int, type: Byte): ControlPacket {
        val matchingPacket = messageFlow
            .filterIsInstance<MqttServiceWorkerMessage.ControlPacketReceived>()
            .first { it.packetId == packetId && it.controlPacketType == type }
        val buffer = JsBuffer(matchingPacket.packetBuffer, limit = matchingPacket.packetBuffer.length)
        return factory.from(buffer)
    }

    fun publish(pub: IPublishMessage) = GlobalScope.promise {
        val qos0Pub = if (pub.qualityOfService == QualityOfService.AT_MOST_ONCE) {
            (pub.serialize() as JsBuffer).buffer
        } else {
            null
        }
        val publishPacketId = if (qos0Pub != null) {
            NO_PACKET_ID
        } else {
            persistence.writePubGetPacketId(broker, pub)
        }
        serviceWorker.postMessage(
            MqttServiceWorkerMessage.SendControlPacket(
                broker.identifier,
                factory.protocolVersion,
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

    fun unsubscribe(unsub: IUnsubscribeRequest) = GlobalScope.promise {
        val packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        val unsuback = async {
            val packet =
                awaitControlPacketReceivedMatching(packetId, IUnsubscribeAcknowledgment.controlPacketValue)
            packet as IUnsubscribeAcknowledgment
        }
        serviceWorker.postMessage(
            MqttServiceWorkerMessage.SendControlPacket(
                broker.identifier,
                factory.protocolVersion,
                unsub.controlPacketValue,
                packetId
            )
        )
        UnsubscribeOperation(packetId, unsuback)
    }

    fun stopListening() {
        producerScope?.channel?.close()
        producerScope = null
    }

    fun shutdown() {
        serviceWorker.postMessage(
            MqttServiceWorkerMessage.Shutdown(
                broker.identifier,
                factory.protocolVersion
            )
        )
        stopListening()
    }

    fun ControlPacket.serialize(): ReadBuffer {
        val size = packetSize()
        val buffer = PlatformBuffer.allocate(size)
        serialize(buffer)
        return buffer
    }

    companion object {
        fun broadcastChannelName(brokerId: Int, protocolVersion: Byte) = "mqtt-$brokerId:$protocolVersion"
    }
}