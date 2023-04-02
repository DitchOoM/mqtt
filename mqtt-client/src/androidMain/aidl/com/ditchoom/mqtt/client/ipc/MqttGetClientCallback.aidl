package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.IPCMqttClient;

interface MqttGetClientCallback {
    void onClientReady(IPCMqttClient client, int brokerId, byte protocolVersion);
    void onClientNotFound();
}