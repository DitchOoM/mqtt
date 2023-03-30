package com.ditchoom.mqtt.client.ipc;

interface OnMqttCompletionCallback {
    void onSuccess();
    void onError(String messageOrNull);
}