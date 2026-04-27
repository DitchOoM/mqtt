package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.buffer.JvmBuffer;

interface MqttMessageTransferredCallback {
    int id();
    void onControlPacketSent(in JvmBuffer controlPacket);
    void onControlPacketReceived(in JvmBuffer controlPacket);

}