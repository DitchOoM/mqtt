package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import kotlin.jvm.JvmInline

/**
 * Wire model for a single SUBACK return code byte.
 */
@ProtocolMessage
@JvmInline
value class SubAckReturnCodeWire(val raw: UByte)

/**
 * Wire model for SUBACK packet body: packet identifier followed by return code list.
 */
@ProtocolMessage
data class SubAckWire(
    val packetIdentifier: UShort,
    @RemainingBytes val returnCodes: List<SubAckReturnCodeWire>,
)
