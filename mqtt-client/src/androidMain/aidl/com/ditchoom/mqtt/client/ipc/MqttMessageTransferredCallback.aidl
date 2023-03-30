package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.buffer.JvmBuffer;

interface MqttMessageTransferredCallback {

    void onControlPacketSent(in JvmBuffer controlPacket);
    void onControlPacketReceived(byte byte1, int remainingLength, in JvmBuffer controlPacket);

}