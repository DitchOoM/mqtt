package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Wire model for simple ACK packets (PUBACK, PUBREC, PUBREL, PUBCOMP, UNSUBACK).
 * All share the same body format: a 2-byte packet identifier.
 */
@ProtocolMessage
@JvmInline
value class AckWire(val packetId: UShort)
