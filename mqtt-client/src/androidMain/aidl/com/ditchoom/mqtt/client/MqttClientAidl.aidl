// MqttClientAidl.aidl
package com.ditchoom.mqtt.client;

import com.ditchoom.mqtt.client.IpcMqttServerToClientMessage;
import com.ditchoom.mqtt.client.OnMqttMessageCallback;
import com.ditchoom.mqtt.client.OnMqttCompletionCallback;
import com.ditchoom.buffer.JvmBuffer;

interface MqttClientAidl {
    void subscribeQueued(int packetIdentifier, OnMqttCompletionCallback cb);
    void publishQueued(int packetIdentifier, in JvmBuffer nullablleQos0Buffer, OnMqttCompletionCallback cb);
    void unsubscribeQueued(int packetIdentifier, OnMqttCompletionCallback cb);

    void registerObserver(IpcMqttServerToClientMessage observer);
    void unregisterObserver(IpcMqttServerToClientMessage observer);

    JvmBuffer currentConnectionAcknowledgmentOrNull();
    void awaitConnectivity(OnMqttMessageCallback cb);

    long pingCount();
    long pingResponseCount();

    long connectionCount();
    long connectionAttempts();

    void sendDisconnect(OnMqttCompletionCallback cb);
    void shutdown(boolean sendDisconnect, OnMqttCompletionCallback cb);
}