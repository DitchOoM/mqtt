package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.IpcMqttServerToClientMessage
import com.ditchoom.mqtt.client.MqttClientAidl
import com.ditchoom.mqtt.client.OnMqttMessageCallback
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidMqttClientIPCClient(
    scope: CoroutineScope,
    private val aidl: MqttClientAidl,
    broker: MqttBroker,
    persistence: Persistence,
) : MqttClientIPCClient(scope, broker, persistence) {

    override val packetFactory: ControlPacketFactory = broker.connectionRequest.controlPacketFactory
    private val cb = object : IpcMqttServerToClientMessage.Stub() {
        override fun onControlPacketSent(controlPacket: JvmBuffer) {
            controlPacket.resetForRead()
            val packet = packetFactory.from(controlPacket)
            onControlPacketSent(packet)
        }

        override fun onControlPacketReceived(byte1: Byte, remainingLength: Int, controlPacket: JvmBuffer) {
            val packet = packetFactory.from(controlPacket, byte1.toUByte(), remainingLength)
            onIncomingControlPacket(packet)
        }
    }
    init {
        aidl.registerObserver(cb)
    }

    fun register(observer: IpcMqttServerToClientMessage) {
        aidl.registerObserver(observer)
    }

    fun unregister(observer: IpcMqttServerToClientMessage) {
        aidl.unregisterObserver(observer)
    }

    override suspend fun sendPublish(packetId: Int, pubBuffer: PlatformBuffer) =
        suspendCoroutine { aidl.publishQueued(packetId, pubBuffer as JvmBuffer, SuspendingCoroutine("sendPublish", it)) }

    override suspend fun sendSubscribe(packetId: Int) =
        suspendCoroutine { aidl.subscribeQueued(packetId, SuspendingCoroutine("sendSubscribe", it)) }

    override suspend fun sendUnsubscribe(packetId: Int) =
        suspendCoroutine { aidl.unsubscribeQueued(packetId, SuspendingCoroutine("sendUnsubscribe", it)) }

    override suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? {
        val buffer = aidl.currentConnectionAcknowledgmentOrNull() ?: return null
        buffer.resetForRead()
        return packetFactory.from(buffer) as? IConnectionAcknowledgment
    }

    override suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        return suspendCoroutine {
            aidl.awaitConnectivity(object : OnMqttMessageCallback.Stub() {
                override fun onMessage(buffer: JvmBuffer) {
                    buffer.resetForRead()
                    it.resume(packetFactory.from(buffer) as IConnectionAcknowledgment)
                }
            })
        }
    }

    override suspend fun pingCount(): Long {
        return aidl.pingCount()
    }

    override suspend fun pingResponseCount(): Long {
        return aidl.pingResponseCount()
    }

    override fun observe(filter: Topic): Flow<IPublishMessage> =
        incomingPackets.filterIsInstance<IPublishMessage>().filter { filter.matches(it.topic) }

    override suspend fun sendDisconnect() {
        suspendCoroutine { aidl.sendDisconnect(SuspendingCoroutine("", it)) }
    }

    override suspend fun connectionCount(): Long {
        return aidl.connectionCount()
    }

    override suspend fun connectionAttempts(): Long {
        return aidl.connectionAttempts()
    }

    override suspend fun shutdown(sendDisconnect: Boolean) {
        aidl.unregisterObserver(cb)
        suspendCoroutine { aidl.shutdown(sendDisconnect, SuspendingCoroutine("", it)) }
    }
}
