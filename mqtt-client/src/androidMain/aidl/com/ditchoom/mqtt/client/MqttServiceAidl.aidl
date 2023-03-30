package com.ditchoom.mqtt.client;

import com.ditchoom.mqtt.client.MqttIpcClientCallback;
import com.ditchoom.mqtt.client.OnMqttCompletionCallback;

interface MqttServiceAidl {
    void startAll(OnMqttCompletionCallback completion);
    void start(int brokerId, byte protocolVersion, OnMqttCompletionCallback completion);

    void stopAll(OnMqttCompletionCallback completion);
    void stop(int brokerId, byte protocolVersion, OnMqttCompletionCallback completion);

    void requestClientOrNull(int brokerId, byte protocolVersion, MqttIpcClientCallback callback);
}