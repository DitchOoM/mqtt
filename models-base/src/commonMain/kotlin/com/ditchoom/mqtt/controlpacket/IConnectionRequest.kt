package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer

interface IConnectionRequest : ControlPacket {
    val protocolName: String
    val protocolVersion: Int
    val hasUserName: Boolean
    val hasPassword: Boolean
    val cleanStart: Boolean
    val keepAliveTimeoutSeconds: UShort

    /** Single source of truth for will message configuration. */
    val will: WillConfig

    // Derived will properties (backward compatible)
    val willFlag: Boolean get() = will is WillConfig.Enabled
    val willRetain: Boolean get() = (will as? WillConfig.Enabled)?.retain ?: false
    val willQos: QualityOfService get() = (will as? WillConfig.Enabled)?.qos ?: QualityOfService.AT_MOST_ONCE
    val willTopic: TopicName? get() = (will as? WillConfig.Enabled)?.topic
    val willPayload: ReadBuffer? get() = (will as? WillConfig.Enabled)?.payload

    // MQTT 5 Variable Header Properties
    val sessionExpiryIntervalSeconds: ULong? get() = null
    val receiveMaximum: UShort get() = UShort.MAX_VALUE
    val maxPacketSize: ULong get() = ULong.MAX_VALUE
    val topicAliasMax: UShort? get() = null

    // Mqtt Variable Header
    val clientIdentifier: String
    val userName: String?
    val password: String?

    // MQTT 5 Payload Will Properties
    val willDelayIntervalSeconds: Long get() = 0L
    val payloadFormatIndicator: Boolean get() = false
    val messageExpiryIntervalSeconds: Long? get() = null
    val contentType: String? get() = null
    val responseTopic: TopicName? get() = null
    val correlationData: ReadBuffer? get() = null
    val userProperty: List<Pair<String, String>> get() = emptyList()
}
