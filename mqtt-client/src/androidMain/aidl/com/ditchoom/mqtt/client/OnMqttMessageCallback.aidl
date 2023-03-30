package com.ditchoom.mqtt.client;

import com.ditchoom.buffer.JvmBuffer;

interface OnMqttMessageCallback {
    void onMessage(in JvmBuffer buffer);
}