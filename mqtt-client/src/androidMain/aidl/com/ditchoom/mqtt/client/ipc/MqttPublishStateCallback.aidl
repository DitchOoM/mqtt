package com.ditchoom.mqtt.client.ipc;

/**
 * IPC callback for observing publish message state transitions.
 * State values: 0=QUEUED, 1=SENT, 2=PUBREC_RECEIVED, 3=PUBREL_SENT, 4=ACKNOWLEDGED, 5=COMPLETE.
 */
oneway interface MqttPublishStateCallback {
    void onStateChanged(int packetId, int state);
}
