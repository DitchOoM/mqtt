package com.ditchoom.mqtt.client.ipc;

import com.ditchoom.mqtt.client.ipc.MqttGetClientCallback;
import com.ditchoom.mqtt.client.ipc.MqttCompletionCallback;

interface IPCMqttService {
    void startAll(MqttCompletionCallback completion);
    void start(int brokerId, byte protocolVersion, MqttCompletionCallback completion);

    void stopAll(MqttCompletionCallback completion);
    void stop(int brokerId, byte protocolVersion, MqttCompletionCallback completion);

    void requestClientOrNull(int brokerId, byte protocolVersion, MqttGetClientCallback callback);
}