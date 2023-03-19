package com.ditchoom.mqtt

import com.ditchoom.mqtt.controlpacket.format.ReasonCode

open class MqttException(msg: String, val reasonCode: UByte) : Exception(msg)
open class MalformedPacketException(msg: String) : MqttException(msg, 0x81.toUByte())
open class ProtocolError(msg: String) : MqttException(msg, 0x82.toUByte())

class MalformedInvalidVariableByteInteger(value: Int) : MqttException(
    "Malformed Variable Byte Integer: This " +
        "property must be a number between 0 and %VARIABLE_BYTE_INT_MAX . Read value was: $value",
    ReasonCode.MALFORMED_PACKET.byte
)
