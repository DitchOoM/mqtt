package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.MalformedPacketException
import kotlin.jvm.JvmInline

/**
 * MQTT fixed-header byte: top nibble = packet type, bottom nibble = packet-specific flags.
 * Used as the `@DispatchOn` discriminator for the v4 and v5 sealed control-packet trees.
 *
 * The flag-extraction helpers cover every packet type's reserved-vs-meaningful nibble:
 * PUBLISH uses dup/qos/retain; PUBREL/SUBSCRIBE/UNSUBSCRIBE pin the low nibble to `0010`;
 * other packet types pin it to `0000`. The processor enforces those reserved values via
 * each variant's `@PacketType(wire = …)` literal.
 *
 * Body-length framing is supplied at the sealed-parent level via
 * `@FramedBy(MqttRemainingLengthCodec::class, after = "header")`, so this class carries
 * no companion-level framing implementation.
 */
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    init {
        if (packetType == 3 && (raw.toInt() and 0b110) == 0b110) {
            throw MalformedPacketException(
                "PUBLISH QoS = 3 is malformed (both QoS bits set); only QoS 0/1/2 are valid.",
            )
        }
    }

    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val flags: Int get() = raw.toInt() and 0x0F

    val publishDup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val publishQos: Int get() = (raw.toInt() shr 1) and 0x3
    val publishRetain: Boolean get() = raw.toInt() and 1 == 1
    val publishHasPacketIdentifier: Boolean get() = publishQos > 0
}
