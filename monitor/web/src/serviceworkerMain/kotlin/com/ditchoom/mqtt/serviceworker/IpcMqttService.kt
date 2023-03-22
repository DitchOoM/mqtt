package com.ditchoom.mqtt.serviceworker

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.khronos.webgl.Uint8Array
import org.w3c.dom.BroadcastChannel
import org.w3c.dom.MessageEvent

class IpcMqttService(val service: MqttService, val broadcastChannelMap: HashMap<Pair<Int, Byte>, BroadcastChannel>) {
    private val map = HashMap<Pair<Int, Int>, MqttClient>()
    fun onIncomingMessageEvent(event: MessageEvent): MqttServiceWorkerMessage? {
        console.log("mqtt service incoming event")
        val m = MqttServiceWorkerMessage.from(event.data) ?: return null
        when (m) {
            is MqttServiceWorkerMessage.BrokerAdded -> onBrokerAdded(m.brokerId, m.protocolVersion)
            is MqttServiceWorkerMessage.RemoveAllBrokersAndStop -> removeAllBrokersAndStop()
            is MqttServiceWorkerMessage.SendControlPacket -> sendControlPacket(
                m.brokerId,
                m.protocolVersion,
                m.controlPacketType,
                m.packetId,
                m.qos0PubPayload
            )

            is MqttServiceWorkerMessage.Shutdown -> shutdown(m.brokerId, m.protocolVersion)
            else -> {}
        }
        return m
    }

    fun onBrokerAdded(brokerId: Int, protocolVersion: Int) {

        console.log("onBrokerAdded $brokerId $protocolVersion")
        GlobalScope.launch {
            val all = service.allMqttBrokers()
            console.log("all brokers ${all.joinToString()}", all.toTypedArray())
            val broker = all
                .first { it.identifier == brokerId && it.connectionRequest.protocolVersion == protocolVersion }
            console.log("broker persisted, now stay connected")
            val client = service.stayConnected(broker)
            console.log("stay connected")
            map[Pair(brokerId, protocolVersion)] = client
        }
    }

    fun removeAllBrokersAndStop() {
        GlobalScope.launch {
            val removeJobs = service.allMqttBrokers().map {
                service.removeBroker(it).job
            }
            joinAll(*removeJobs.toTypedArray())
            service.stop()
            map.clear()
        }
    }

    fun sendControlPacket(
        brokerId: Int, protocolVersion: Int, controlPacketType: Byte,
        packetId: Int, publishBuffer: Uint8Array?
    ) {
        val client = map[Pair(brokerId, protocolVersion)] ?: return
        GlobalScope.launch {
            when (controlPacketType) {
                IPublishMessage.controlPacketValue -> {
                    val pub0 = publishBuffer
                        ?.let { JsBuffer(publishBuffer, limit = publishBuffer.length) }
                        ?.let { client.controlPacketFactory().from(it) as? IPublishMessage }
                    client.sendQueuedPublishMessage(packetId, pub0)
                }

                ISubscribeRequest.controlPacketValue -> {
                    client.sendQueuedSubscribeMessage(packetId)
                }

                IUnsubscribeRequest.controlPacketValue -> {
                    client.sendQueuedUnsubscribeMessage(packetId)
                }

                else -> {}
            }
        }
    }

    fun controlPacketSent(
        brokerId: Int, protocolVersion: Int, controlPacketType: Byte,
        packetId: Int, publishBuffer: Uint8Array
    ) {
        broadcastChannelMap[Pair(brokerId, protocolVersion.toByte())]
            ?.postMessage(
                MqttServiceWorkerMessage.ControlPacketSent(
                    brokerId, protocolVersion, controlPacketType, packetId, publishBuffer
                )
            )
    }

    fun controlPacketReceived(
        brokerId: Int, protocolVersion: Int, controlPacketType: Byte,
        packetId: Int, publishBuffer: Uint8Array
    ) {
        broadcastChannelMap[Pair(brokerId, protocolVersion.toByte())]
            ?.postMessage(
                MqttServiceWorkerMessage.ControlPacketReceived(
                    brokerId, protocolVersion, controlPacketType, packetId, publishBuffer
                )
            )
    }

    fun shutdown(brokerId: Int, protocolVersion: Int) {
        val client = map.remove(Pair(brokerId, protocolVersion)) ?: return
        GlobalScope.launch {
            client.shutdown()
            broadcastChannelMap.remove(Pair(brokerId, protocolVersion.toByte()))?.close()
        }
    }
}