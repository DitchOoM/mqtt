package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer

/**
 * Will message configuration for MQTT CONNECT packets.
 *
 * Eliminates the impossible state where willFlag=true but willTopic/willPayload is null
 * (or vice versa) by making the will topic and payload required fields of [Enabled].
 *
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#_Toc1477340">
 *     MQTT 5.0 §3.1.2.5 Will Flag</a>
 */
sealed interface WillConfig {
    data object Disabled : WillConfig

    data class Enabled(
        val topic: TopicName,
        val payload: ReadBuffer,
        val qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        val retain: Boolean = false,
    ) : WillConfig
}
