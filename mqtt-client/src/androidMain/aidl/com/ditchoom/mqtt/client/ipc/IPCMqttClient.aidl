// IPCMqttClient.aidl
package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.MqttMessageTransferredCallback;
import com.ditchoom.mqtt.client.ipc.MqttMessageCallback;
import com.ditchoom.mqtt.client.ipc.MqttCompletionCallback;
import com.ditchoom.buffer.JvmBuffer;

interface IPCMqttClient {
    void subscribeQueued(int packetIdentifier, MqttCompletionCallback cb);
    void publishQueued(int packetIdentifier, in JvmBuffer nullablleQos0Buffer, MqttCompletionCallback cb);
    void unsubscribeQueued(int packetIdentifier, MqttCompletionCallback cb);

    void registerObserver(MqttMessageTransferredCallback observer);
    void unregisterObserver(MqttMessageTransferredCallback observer);

    JvmBuffer currentConnectionAcknowledgmentOrNull();
    void awaitConnectivity(MqttMessageCallback cb);

    long pingCount();
    long pingResponseCount();

    long connectionCount();
    long connectionAttempts();

    void sendDisconnect(MqttCompletionCallback cb);
    void shutdown(boolean sendDisconnect, MqttCompletionCallback cb);
}