package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.connection.MqttBroker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.w3c.dom.MessageChannel
import org.w3c.dom.MessagePort

class JsRemoteMqttServiceClient(service: LocalMqttService, private val port: MessagePort) :
    RemoteMqttServiceClient(service) {
    private val clients = HashMap<Byte, HashMap<Int, JsRemoteMqttClient>>()
    private var closeCb: () -> Unit = {}
    private var nextMessageId = 0
    private val onMessageFlow = callbackFlow {
        closeCb = { channel.close() }
        port.onmessage = {
            trySend(it)
        }
        awaitClose()
    }

    override val startAllCb: suspend () -> Unit = {
        val messageId = nextMessageId++
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_SERVICE_START_ALL, messageId))
        awaitMessage(MESSAGE_TYPE_SERVICE_START_ALL_RESPONSE, messageId)
    }
    override val startCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        val messageId = nextMessageId++
        port.postMessage(
            buildBrokerIdProtocolVersionMessage(
                MESSAGE_TYPE_SERVICE_START,
                brokerId,
                protocolVersion,
                messageId
            )
        )
        awaitMessage(MESSAGE_TYPE_SERVICE_START_RESPONSE, messageId)
    }
    override val stopAllCb: suspend () -> Unit = {
        val messageId = nextMessageId++
        port.postMessage(buildSimpleMessage(MESSAGE_TYPE_SERVICE_STOP_ALL, messageId))
        awaitMessage(MESSAGE_TYPE_SERVICE_STOP_ALL_RESPONSE, messageId)
    }
    override val stopCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        val messageId = nextMessageId++
        port.postMessage(
            buildBrokerIdProtocolVersionMessage(
                MESSAGE_TYPE_SERVICE_STOP,
                brokerId,
                protocolVersion,
                messageId
            )
        )
        awaitMessage(MESSAGE_TYPE_SERVICE_STOP_RESPONSE, messageId)
    }

    private suspend fun awaitMessage(messageType: String, messageId: Int) {
        onMessageFlow.first {
            val obj = it.data?.asDynamic()
            obj[MESSAGE_TYPE_KEY] == messageType && obj[MESSAGE_INT_KEY] == messageId
        }
    }

    fun close() = closeCb()

    override suspend fun getClient(broker: MqttBroker): JsRemoteMqttClient? {
        val brokerId = broker.brokerId
        val protocolVersion = broker.protocolVersion
        val persistence = service.getPersistence(protocolVersion.toInt())
        persistence.brokerWithId(brokerId) ?: return null // validate broker still exists
        return clients.getOrPut(protocolVersion) { HashMap() }
            .getOrPut(brokerId) {
                val messageChannel = MessageChannel()
                port.postMessage(
                    buildBrokerIdProtocolVersionMessage(
                        MESSAGE_TYPE_REGISTER_CLIENT,
                        brokerId,
                        protocolVersion
                    ),
                    arrayOf(messageChannel.port2)
                )
                val message = onMessageFlow.filter {
                    val obj = it.data?.asDynamic()
                    obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_REGISTER_CLIENT_SUCCESS ||
                        obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_REGISTER_CLIENT_NOT_FOUND
                }.first()
                val obj = message.data?.asDynamic()
                if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_REGISTER_CLIENT_SUCCESS) {
                    val c = JsRemoteMqttClient(service.scope, messageChannel.port1, broker, persistence)
                    c.startObservingMessages()
                    c
                } else if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_REGISTER_CLIENT_NOT_FOUND) {
                    return null
                } else {
                    console.error("Invalid message received when requesting client", obj)
                    throw IllegalStateException("Invalid message received when requesting client $obj")
                }
            }
    }
}
