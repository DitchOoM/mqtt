package com.ditchoom.mqtt.client.ipc

import android.util.Log
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.shared
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.TopicFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidRemoteMqttClient(
    scope: CoroutineScope,
    private val aidl: IPCMqttClient,
    broker: MqttBroker,
    persistence: Persistence,
) : RemoteMqttClient(scope, broker, persistence) {
    override val bufferFactory: BufferFactory = BufferFactory.shared()

    override val packetFactory: ControlPacketFactory = broker.connectionRequest.controlPacketFactory

    private val cb =
        object : MqttMessageTransferredCallback.Stub() {
            private val id = UUID.randomUUID().leastSignificantBits.toInt()

            override fun id(): Int = id

            override fun onControlPacketSent(controlPacket: JvmBuffer) {
                controlPacket.resetForRead()
                val packet = packetFactory.from(controlPacket)
                Log.i("RAHUL", "IPCOUT:  $packet")
                onControlPacketSent(packet)
            }

            override fun onControlPacketReceived(
                byte1: Byte,
                remainingLength: Int,
                controlPacket: JvmBuffer,
            ) {
                val packet = packetFactory.from(controlPacket, byte1.toUByte(), remainingLength)
                Log.i("RAHUL", "IPCIN :  $packet")
                onIncomingControlPacket(packet)
            }
        }

    private val publishStateCb = object : MqttPublishStateCallback.Stub() {
        override fun onStateChanged(packetId: Int, state: Int) {
            // Bridge IPC state callback → processor's state flows
            // State values: 0=QUEUED, 1=SENT, 2=PUBREC_RECEIVED, 3=PUBREL_SENT, 4=ACKNOWLEDGED, 5=COMPLETE
        }
    }

    init {
        aidl.registerObserver(cb)
        aidl.registerPublishStateObserver(publishStateCb)
    }

    fun register(observer: MqttMessageTransferredCallback) {
        aidl.registerObserver(observer)
    }

    fun unregister(observer: MqttMessageTransferredCallback) {
        aidl.unregisterObserver(observer)
    }

    override suspend fun sendPublish(
        packetId: Int,
        pubBuffer: PlatformBuffer,
    ) = suspendCoroutine {
        aidl.publishQueued(
            packetId,
            pubBuffer as JvmBuffer,
            SuspendingMqttCompletionCallback("sendPublish", it),
        )
    }

    override suspend fun sendSubscribe(packetId: Int) =
        suspendCoroutine { aidl.subscribeQueued(packetId, SuspendingMqttCompletionCallback("sendSubscribe", it)) }

    override suspend fun sendUnsubscribe(packetId: Int) =
        suspendCoroutine { aidl.unsubscribeQueued(packetId, SuspendingMqttCompletionCallback("sendUnsubscribe", it)) }

    override suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? {
        val buffer = aidl.currentConnectionAcknowledgmentOrNull() ?: return null
        buffer.resetForRead()
        return packetFactory.from(buffer) as? IConnectionAcknowledgment
    }

    override suspend fun awaitConnectivity(): IConnectionAcknowledgment =
        suspendCoroutine {
            aidl.awaitConnectivity(
                object : MqttMessageCallback.Stub() {
                    override fun onMessage(buffer: JvmBuffer) {
                        buffer.resetForRead()
                        it.resume(packetFactory.from(buffer) as IConnectionAcknowledgment)
                    }
                },
            )
        }

    override suspend fun pingCount(): Long = aidl.pingCount()

    override suspend fun pingResponseCount(): Long = aidl.pingResponseCount()

    override fun observe(filter: TopicFilter): Flow<IPublishMessage> =
        incomingPackets.filterIsInstance<IPublishMessage>().filter { filter.matches(it.topic) }

    override suspend fun sendDisconnect() {
        suspendCoroutine { aidl.sendDisconnect(SuspendingMqttCompletionCallback("sendDisconnect", it)) }
    }

    override suspend fun connectionCount(): Long = aidl.connectionCount()

    override suspend fun connectionAttempts(): Long = aidl.connectionAttempts()

    override suspend fun shutdown(
        sendDisconnect: Boolean,
        drain: Boolean,
    ) {
        aidl.unregisterObserver(cb)
        aidl.unregisterPublishStateObserver(publishStateCb)
        suspendCoroutine { aidl.shutdown(sendDisconnect, SuspendingMqttCompletionCallback("shutdown", it)) }
    }
}
