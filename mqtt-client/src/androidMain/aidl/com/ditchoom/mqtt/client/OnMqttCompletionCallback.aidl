package com.ditchoom.mqtt.client;

interface OnMqttCompletionCallback {
    void onSuccess();
    void onError(String messageOrNull);
}