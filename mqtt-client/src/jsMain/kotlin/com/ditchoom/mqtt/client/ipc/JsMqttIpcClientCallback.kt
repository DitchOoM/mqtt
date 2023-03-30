package com.ditchoom.mqtt.client.ipc

interface JsMqttIpcClientCallback {
    fun onClientReady(client: MqttClientIPCServer, brokerId: Int, protocolVersion: Byte)
    fun onClientNotFound()
}
