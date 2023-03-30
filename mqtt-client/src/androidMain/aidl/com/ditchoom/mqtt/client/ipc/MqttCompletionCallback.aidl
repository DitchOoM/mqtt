package com.ditchoom.mqtt.client.ipc;

interface MqttCompletionCallback {
    void onSuccess();
    void onError(String messageOrNull);
}