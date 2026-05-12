package com.ditchoom.mqtt

import com.ditchoom.mqtt.controlpacket.format.ReasonCode

open class MqttException(
    msg: String,
    val reasonCode: UByte,
) : Exception(msg)

open class MalformedPacketException(
    msg: String,
) : MqttException(msg, 0x81.toUByte())

open class ProtocolError(
    msg: String,
) : MqttException(msg, 0x82.toUByte())

class MalformedInvalidVariableByteInteger(
    value: Int,
) : MqttException(
        "Malformed Variable Byte Integer: This " +
            "property must be a number between 0 and %VARIABLE_BYTE_INT_MAX . Read value was: $value",
        ReasonCode.MALFORMED_PACKET.byte,
    )

/**
 * Thrown when a PUBLISH arrives on a topic with no codec registered in
 * the connection's TopicCodecRegistry and no `defaultPublishCodec`
 * configured at `MqttClient.start(...)`. The fix is to call
 * `MqttClient.subscribe<P>(filter, codec, handler)` (which registers the
 * codec before SUBSCRIBE leaves the wire), or to supply a fallback codec
 * (typically `OpaqueBytesHandleCodec` for log/dead-letter handling).
 */
class MissingCodecException(
    val topicName: String,
) : MqttException(
        "No codec registered for topic '$topicName'. Register via " +
            "MqttClient.subscribe<P>(filter, codec, handler) before SUBSCRIBE, " +
            "or supply a defaultPublishCodec to MqttClient.start(...).",
        ReasonCode.UNSPECIFIED_ERROR.byte,
    )
