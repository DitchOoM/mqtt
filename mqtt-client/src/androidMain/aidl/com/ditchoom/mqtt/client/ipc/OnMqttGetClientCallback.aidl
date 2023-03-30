package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.IPCMqttClient;

interface OnMqttGetClientCallback {
    void onClientReady(IPCMqttClient client, int brokerId, byte protocolVersion);
    void onClientNotFound();
}