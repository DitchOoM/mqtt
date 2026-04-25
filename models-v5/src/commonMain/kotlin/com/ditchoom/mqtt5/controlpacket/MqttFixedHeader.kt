package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

// TODO relocate to models-base when v4 production migration moves off the hand-written
// `ControlPacketV4Factory.from(...)` dispatcher. Both v4 and v5 dispatch on the identical
// fixed-header byte; co-locating the type avoids duplication.

/**
 * MQTT fixed-header byte: top nibble = packet type, bottom nibble = packet-specific flags.
 * Used as the `@DispatchOn` discriminator for the v5 sealed control-packet tree.
 *
 * The flag-extraction helpers cover every packet type's reserved-vs-meaningful nibble:
 * PUBLISH uses dup/qos/retain; PUBREL/SUBSCRIBE/UNSUBSCRIBE pin the low nibble to `0010`;
 * other packet types pin it to `0000`. The processor enforces those reserved values via
 * each variant's `@PacketType(wire = …)` literal.
 */
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val flags: Int get() = raw.toInt() and 0x0F

    val publishDup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val publishQos: Int get() = (raw.toInt() shr 1) and 0x3
    val publishRetain: Boolean get() = raw.toInt() and 1 == 1
    val publishHasPacketIdentifier: Boolean get() = publishQos > 0
}
