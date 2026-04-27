package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.shared
import kotlinx.coroutines.launch
import org.w3c.dom.MessageEvent
import org.w3c.dom.MessagePort

class JsRemoteMqttServiceWorker(
    private val serviceServer: RemoteMqttServiceWorker,
) {
    private val scope = serviceServer.service.scope
    internal val mqttService = serviceServer.service

    fun processIncomingMessage(m: MessageEvent): MessagePort? {
        if (m.data == MESSAGE_IPC_MQTT_SERVICE_REGISTRATION) {
            val messagePort = m.ports[0]
            messagePort.onmessage = {
                val data = it.data.asDynamic()
                if (data[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_REGISTER_CLIENT) {
                    scope.launch {
                        requestClientAndPostMessage(data, it.ports[0])
                    }
                } else {
                    processIncomingMessage(it)
                }
            }
            messagePort.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK)
            return messagePort
        }
        val obj = m.data.asDynamic()
        val brokerIdProtocolPair = readBrokerIdProtocolVersionMessage(obj)
        if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_SERVICE_START_ALL) {
            serviceServer.service.scope.launch { serviceServer.startAll() }
        } else if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_SERVICE_START && brokerIdProtocolPair != null) {
            val (brokerId, protocolVersion) = brokerIdProtocolPair
            serviceServer.service.scope.launch { serviceServer.start(brokerId, protocolVersion) }
            (m.target as MessagePort).postMessage(MESSAGE_TYPE_SERVICE_START_RESPONSE)
        } else if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_SERVICE_STOP && brokerIdProtocolPair != null) {
            val (brokerId, protocolVersion) = brokerIdProtocolPair
            serviceServer.service.scope.launch { serviceServer.stop(brokerId, protocolVersion) }
            (m.target as MessagePort).postMessage(MESSAGE_TYPE_SERVICE_STOP_RESPONSE)
        } else if (obj[MESSAGE_TYPE_KEY] == MESSAGE_TYPE_SERVICE_STOP_ALL) {
            serviceServer.service.scope.launch { serviceServer.stopAll() }
            (m.target as MessagePort).postMessage(MESSAGE_TYPE_SERVICE_STOP_ALL_RESPONSE)
        }
        return null
    }

    private suspend fun requestClientAndPostMessage(
        obj: dynamic,
        port: MessagePort,
    ) {
        val (brokerId, protocolVersion) = readBrokerIdProtocolVersionMessage(obj) ?: return
        val client = serviceServer.requestClientOrNull(brokerId, protocolVersion)
        if (client != null || client?.client?.isStopped() == true) {
            val ipcClientServer = JsRemoteMqttClientWorker(client, port)
            ipcClientServer.registerOnMessageObserver()
            port.postMessage(
                buildBrokerIdProtocolVersionMessage(
                    MESSAGE_TYPE_REGISTER_CLIENT_SUCCESS,
                    brokerId,
                    protocolVersion,
                ),
            )
            client.observers += { incoming, packet ->
                // Serialize at the IPC boundary — the processor stays in ControlPacket
                // space; only the Web Worker hop needs the wire buffer.
                val buffer = packet.serialize(BufferFactory.shared()) as JsBuffer
                val packetMessage =
                    if (incoming) {
                        sendIncomingControlPacketMessage(buffer)
                    } else {
                        buildOutgoingControlPacketMessage(buffer)
                    }
                port.postMessage(packetMessage)
            }
        } else {
            port.postMessage(buildSimpleMessage(MESSAGE_TYPE_REGISTER_CLIENT_NOT_FOUND))
            port.close()
        }
    }
}
