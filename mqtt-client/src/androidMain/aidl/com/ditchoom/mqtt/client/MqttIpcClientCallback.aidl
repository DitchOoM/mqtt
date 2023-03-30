package com.ditchoom.mqtt.client;

import com.ditchoom.mqtt.client.MqttClientAidl;

interface MqttIpcClientCallback {
    void onClientReady(MqttClientAidl client, int brokerId, byte protocolVersion);
    void onClientNotFound();
}