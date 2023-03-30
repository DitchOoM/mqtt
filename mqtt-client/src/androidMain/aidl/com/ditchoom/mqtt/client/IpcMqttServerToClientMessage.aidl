package com.ditchoom.mqtt.client;

import com.ditchoom.buffer.JvmBuffer;

interface IpcMqttServerToClientMessage {
//    void onIncomingLog(String log);

    void onControlPacketSent(in JvmBuffer controlPacket);
    void onControlPacketReceived(byte byte1, int remainingLength, in JvmBuffer controlPacket);

//    void onConnected();
//    void onDisconnected();
//    void onReconnectingIn(int seconds);

}