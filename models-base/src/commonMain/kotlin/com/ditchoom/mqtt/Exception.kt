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
 * the connection's TopicCodecRegistry. The fix is to call
 * `MqttClient.observe<P>(filter, codec)` or
 * `MqttClient.subscribe<P>(filter, codec, ...)` before any SUBSCRIBE leaves
 * the wire — both register the codec eagerly. For raw-byte consumers,
 * pass `OpaquePublishPayloadCodec` (one wire-boundary copy, no hidden
 * defaults). Low-level `defaultSingleConnection` users supply
 * `publishCodecForTopic` directly.
 */
class MissingCodecException(
    val topicName: String,
) : MqttException(
        "No codec registered for topic '$topicName'. Register via " +
            "MqttClient.observe<P>(filter, codec) or " +
            "MqttClient.subscribe<P>(filter, codec, ...) before SUBSCRIBE. " +
            "For raw bytes, pass OpaquePublishPayloadCodec.",
        ReasonCode.UNSPECIFIED_ERROR.byte,
    )
