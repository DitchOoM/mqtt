package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * The MQTT specification defines fifteen different types of MQTT Control Packet, for example the PublishMessage packet is
 * used to convey Application Messages.
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477322
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#_Toc514847903
 */
sealed interface ControlPacketV5 : ControlPacket {
    override val mqttVersion: Byte get() = 5

    override val controlPacketFactory: ControlPacketFactory get() = ControlPacketV5Factory

    companion object {
        fun from(buffer: ReadBuffer) = fromTyped(buffer)

        fun fromTyped(buffer: ReadBuffer): ControlPacketV5 {
            val byte1 = buffer.readUnsignedByte()
            val remainingLength = buffer.readVariableByteInteger()
            val remainingBuffer =
                if (remainingLength > 0) {
                    buffer.readBytes(remainingLength)
                } else {
                    ReadBuffer.EMPTY_BUFFER
                }
            return fromTyped(remainingBuffer, byte1, remainingLength)
        }

        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ) = fromTyped(buffer, byte1, remainingLength)

        fun fromTyped(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): ControlPacketV5 {
            val packetValue = (byte1.toUInt() shr 4).toInt()
            return when (packetValue) {
                0 -> throw MalformedPacketException("Reserved packet type 0 is not permitted")
                3 -> {
                    val header = MqttFixedHeader(byte1)
                    if (header.publishQos == 3) {
                        throw MalformedPacketException(
                            "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
                        )
                    }
                    val ctx =
                        publishPropertyDecodeContext()
                            .with(V5PacketCodec.DiscriminatorKey, header)
                    V5PacketPublishCodec.decode(buffer, ctx) { slice -> slice }
                }
                in migratedPacketTypes -> decodeMigrated(buffer, byte1, packetValue, remainingLength)
                else -> throw MalformedPacketException(
                    "Invalid MQTT Control Packet Type: $packetValue Should be in range between 0 and 15 inclusive",
                )
            }
        }

        // Packet types whose decode is delegated to the generated `V5Packet` per-variant codecs.
        // Migrated as part of the @ProtocolMessage rollout.
        private val migratedPacketTypes = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

        private fun decodeMigrated(
            buffer: ReadBuffer,
            byte1: UByte,
            packetValue: Int,
            remainingLength: Int,
        ): V5Packet {
            // PUBREL/SUBSCRIBE/UNSUBSCRIBE pin reserved low-nibble bits to `0010`. Validate before
            // dispatch — the generated codec enforces packetType (top nibble) but not flags.
            val flags = byte1.toInt() and 0x0F
            val expectedFlags = if (packetValue == 6 || packetValue == 8 || packetValue == 10) 0x02 else 0x00
            if (flags != expectedFlags) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for packet type $packetValue must be 0x${
                        expectedFlags.toString(16)
                    }, got 0x${flags.toString(16)}",
                )
            }
            // Variant codecs read body only; populate the discriminator into the decode context
            // for any variant that pulls fields off it (ack-family doesn't, but PUBLISH will once
            // it migrates).
            val ctx = DecodeContext.Empty.with(V5PacketCodec.DiscriminatorKey, MqttFixedHeader(byte1))
            return when (packetValue) {
                1 -> V5PacketConnectCodec.decode<com.ditchoom.buffer.ReadBuffer?>(buffer) { slice ->
                    if (slice.remaining() > 0) slice else null
                }
                2 -> V5PacketConnAckCodec.decode(buffer, ctx)
                4 -> V5PacketPubAckCodec.decode(buffer, ctx)
                5 -> V5PacketPubRecCodec.decode(buffer, ctx)
                6 -> V5PacketPubRelCodec.decode(buffer, ctx)
                7 -> V5PacketPubCompCodec.decode(buffer, ctx)
                8 -> V5PacketSubscribeCodec.decode(buffer, ctx)
                9 -> V5PacketSubAckCodec.decode(buffer, ctx)
                10 -> V5PacketUnsubscribeCodec.decode(buffer, ctx)
                11 -> V5PacketUnsubAckCodec.decode(buffer, ctx)
                12 -> if (remainingLength != 0) {
                    throw MalformedPacketException(
                        "PINGREQ remaining length must be 0, got $remainingLength",
                    )
                } else {
                    V5Packet.PingReq
                }
                13 -> if (remainingLength != 0) {
                    throw MalformedPacketException(
                        "PINGRESP remaining length must be 0, got $remainingLength",
                    )
                } else {
                    V5Packet.PingResp
                }
                14 -> V5PacketDisconnectCodec.decode(buffer, ctx)
                15 -> V5PacketAuthCodec.decode(buffer, ctx)
                else -> throw IllegalStateException("Unreachable: $packetValue not in migratedPacketTypes")
            }
        }
    }
}
