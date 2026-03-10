package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Wire model for CONNACK (Connection Acknowledgment).
 * Variable header: 1 byte acknowledge flags + 1 byte return code.
 */
@ProtocolMessage
data class ConnAckWire(val acknowledgeFlags: UByte, val returnCode: UByte)
