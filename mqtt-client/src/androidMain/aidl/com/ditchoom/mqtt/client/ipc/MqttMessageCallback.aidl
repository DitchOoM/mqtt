package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.buffer.JvmBuffer;

interface MqttMessageCallback {
    void onMessage(in JvmBuffer buffer);
}