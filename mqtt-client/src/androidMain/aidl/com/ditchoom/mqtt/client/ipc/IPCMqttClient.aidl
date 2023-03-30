// IPCMqttClient.aidl
package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.MqttMessageTransferredCallback;
import com.ditchoom.mqtt.client.ipc.OnMqttMessageCallback;
import com.ditchoom.mqtt.client.ipc.OnMqttCompletionCallback;
import com.ditchoom.buffer.JvmBuffer;

interface IPCMqttClient {
    void subscribeQueued(int packetIdentifier, OnMqttCompletionCallback cb);
    void publishQueued(int packetIdentifier, in JvmBuffer nullablleQos0Buffer, OnMqttCompletionCallback cb);
    void unsubscribeQueued(int packetIdentifier, OnMqttCompletionCallback cb);

    void registerObserver(MqttMessageTransferredCallback observer);
    void unregisterObserver(MqttMessageTransferredCallback observer);

    JvmBuffer currentConnectionAcknowledgmentOrNull();
    void awaitConnectivity(OnMqttMessageCallback cb);

    long pingCount();
    long pingResponseCount();

    long connectionCount();
    long connectionAttempts();

    void sendDisconnect(OnMqttCompletionCallback cb);
    void shutdown(boolean sendDisconnect, OnMqttCompletionCallback cb);
}