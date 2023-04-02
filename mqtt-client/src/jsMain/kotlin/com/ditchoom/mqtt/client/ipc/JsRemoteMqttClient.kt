package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.w3c.dom.MessagePort

class JsRemoteMqttClient(
    scope: CoroutineScope,
    private val port: MessagePort,
    broker: MqttBroker,
    persistence: Persistence,
) : RemoteMqttClient(scope, broker, persistence) {
    private var nextMessageId = 0
    override val packetFactory: ControlPacketFactory = broker.connectionRequest.controlPacketFactory
    private var closeCb = {}
    private val messageFlow = callbackFlow {
        closeCb = { channel.close() }
        port.onmessage = {
            trySend(it)
        }
        awaitClose()
    }

    fun startObservingMessages() = scope.launch {
        messageFlow.collect {
            val pair = readControlPacketFromMessageEvent(packetFactory, it)
            if (pair != null) {
                val (incoming, packet) = pair
                if (incoming) {
                    onIncomingControlPacket(packet)
                } else {
                    onControlPacketSent(packet)
                }
            }
        }
    }

    override suspend fun sendPublish(packetId: Int, pubBuffer: PlatformBuffer) {
        val messageId = nextMessageId++
        port.postMessage(buildPacketIdMessage(MESSAGE_TYPE_CLIENT_PUBLISH, packetId, pubBuffer as? JsBuffer, messageId))
        awaitMessage(MESSAGE_TYPE_CLIENT_PUBLISH_COMPLETION, messageId)
    }

    override suspend fun sendSubscribe(packetId: Int) {
        val messageId = nextMessageId++
        port.postMessage(buildPacketIdMessage(MESSAGE_TYPE_CLIENT_SUBSCRIBE, packetId))
        awaitMessage(MESSAGE_TYPE_CLIENT_SUBSCRIBE_COMPLETION, messageId)
    }

    override suspend fun sendUnsubscribe(packetId: Int) {
        val messageId = nextMessageId++
        port.postMessage(buildPacketIdMessage(MESSAGE_TYPE_CLIENT_UNSUBSCRIBE, packetId))
        awaitMessage(MESSAGE_TYPE_CLIENT_UNSUBSCRIBE_COMPLETION, messageId)
    }

    override suspend fun shutdown(sendDisconnect: Boolean) {
        val messageId = nextMessageId++
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_SHUTDOWN, sendDisconnect, messageId))
        awaitMessage(MESSAGE_TYPE_CLIENT_SHUTDOWN_COMPLETION, messageId)
    }

    fun close() {
        closeCb()
    }

    override suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment? {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_CONNACK_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_CONNACK_RESPONSE }
        return readPacketIdMessage(packetFactory, messageEvent.data.asDynamic())?.second as? IConnectionAcknowledgment
    }

    override suspend fun awaitConnectivity(): IConnectionAcknowledgment {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_RESPONSE }
        return checkNotNull(
            readPacketIdMessage(
                packetFactory,
                messageEvent.data.asDynamic()
            )?.second as? IConnectionAcknowledgment
        )
    }

    override suspend fun pingCount(): Long {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_PING_COUNT_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_PING_COUNT_RESPONSE }
        return readLongDataFromMessage(messageEvent.data.asDynamic())
    }

    override suspend fun pingResponseCount(): Long {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_PING_RESPONSE_COUNT_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_PING_RESPONSE_COUNT_RESPONSE }
        return readLongDataFromMessage(messageEvent.data.asDynamic())
    }

    override fun observe(filter: Topic): Flow<IPublishMessage> =
        incomingPackets.filterIsInstance<IPublishMessage>().filter { filter.matches(it.topic) }

    override suspend fun sendDisconnect() {
        val messageId = nextMessageId++
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_SEND_DISCONNECT, messageId))
        awaitMessage(MESSAGE_TYPE_CLIENT_SEND_DISCONNECT_ACK, messageId)
    }

    override suspend fun connectionCount(): Long {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_CONNECTION_COUNT_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_CONNECTION_COUNT_RESPONSE }
        return readLongDataFromMessage(messageEvent.data.asDynamic())
    }

    override suspend fun connectionAttempts(): Long {
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_CLIENT_CONNECTION_ATTEMPTS_REQUEST))
        val messageEvent =
            messageFlow.first { it.data?.asDynamic()[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_CLIENT_CONNECTION_ATTEMPTS_RESPONSE }
        return readLongDataFromMessage(messageEvent.data.asDynamic())
    }

    private suspend fun awaitMessage(messageType: String, messageId: Int) {
        messageFlow.first {
            val obj = it.data?.asDynamic()
            obj[MESSAGE_TYPE_KEY] == messageType && obj[MESSAGE_INT_KEY] == messageId
        }
    }
}
