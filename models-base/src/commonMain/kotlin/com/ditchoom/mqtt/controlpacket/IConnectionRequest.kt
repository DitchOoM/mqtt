package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer

interface IConnectionRequest : ControlPacket {
    val protocolName: String
    val protocolVersion: Int
    val hasUserName: Boolean
    val hasPassword: Boolean
    val willRetain: Boolean
    val willQos: QualityOfService
    val willFlag: Boolean
    val cleanStart: Boolean
    val keepAliveTimeoutSeconds: UShort

    // MQTT 5 Variable Header Properties

    // Null if unsupported, 0 if absent or set to 0.
    val sessionExpiryIntervalSeconds: ULong?
        get() = null
    val receiveMaximum: UShort
        get() = UShort.MAX_VALUE

    val maxPacketSize: ULong
        get() = ULong.MAX_VALUE

    // Null if unsupported, 0 if absent or set to 0.
    val topicAliasMax: UShort?
        get() = null

    // Mqtt Variable Header
    val clientIdentifier: String
    val willTopic: Topic?
    val willPayload: ReadBuffer?
    val userName: String?
    val password: String?

    // MQTT 5 Payload Will Properties
    val willDelayIntervalSeconds: Long get() = 0L
    val payloadFormatIndicator: Boolean get() = false
    val messageExpiryIntervalSeconds: Long? get() = null
    val contentType: String? get() = null
    val responseTopic: Topic? get() = null
    val correlationData: ReadBuffer? get() = null
    val userProperty: List<Pair<String, String>> get() = emptyList()
}
