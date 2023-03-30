package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.OnMqttGetClientCallback;
import com.ditchoom.mqtt.client.ipc.OnMqttCompletionCallback;

interface IPCMqttService {
    void startAll(OnMqttCompletionCallback completion);
    void start(int brokerId, byte protocolVersion, OnMqttCompletionCallback completion);

    void stopAll(OnMqttCompletionCallback completion);
    void stop(int brokerId, byte protocolVersion, OnMqttCompletionCallback completion);

    void requestClientOrNull(int brokerId, byte protocolVersion, OnMqttGetClientCallback callback);
}