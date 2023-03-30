package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.buffer.JvmBuffer;

interface OnMqttMessageCallback {
    void onMessage(in JvmBuffer buffer);
}