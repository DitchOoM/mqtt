package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * The MQTT specification defines fifteen different types of MQTT Control Packet, for example the PublishMessage packet is
 * used to convey Application Messages.
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477322
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#_Toc514847903
 * @param controlPacketValue Value defined under [MQTT 2.1.2]
 * @param direction Direction of Flow defined under [MQTT 2.1.2]
 */
abstract class ControlPacketV4(
    override val controlPacketValue: Byte,
    override val direction: DirectionOfFlow,
    override val flags: Byte = 0b0,
) : ControlPacket {
    override val mqttVersion: Byte = 4
    override val controlPacketFactory = ControlPacketV4Factory

    companion object {
        inline fun <reified WillPayload : Any, reified PublishPayload : Any> fromTyped(buffer: ReadBuffer): ControlPacketV4 {
            val byte1 = buffer.readUnsignedByte()
            val remainingLength = buffer.readVariableByteInteger()
            return fromTyped<WillPayload, PublishPayload>(buffer, byte1, remainingLength)
        }

        fun from(buffer: ReadBuffer) = fromTyped<Unit, Unit>(buffer)

        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ) = fromTyped<Unit, Unit>(buffer, byte1, remainingLength)

        inline fun <reified WillPayload : Any, reified PublishPayload : Any> fromTyped(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): ControlPacketV4 {
            val byte1AsUInt = byte1.toUInt()
            val packetValue = byte1AsUInt.shr(4).toInt()
            return when (packetValue) {
                0 -> Reserved
                1 -> ConnectionRequest.from(buffer)
                2 -> ConnectionAcknowledgment.from(buffer)
                3 -> PublishMessage.from(buffer, byte1, remainingLength)
                4 -> PublishAcknowledgment.from(buffer)
                5 -> PublishReceived.from(buffer)
                6 -> PublishRelease.from(buffer)
                7 -> PublishComplete.from(buffer)
                8 -> SubscribeRequest.from(buffer, remainingLength)
                9 -> SubscribeAcknowledgement.from(buffer, remainingLength)
                10 -> UnsubscribeRequest.from(buffer, remainingLength)
                11 -> UnsubscribeAcknowledgment.from(buffer)
                12 -> PingRequest
                13 -> PingResponse
                14 -> DisconnectNotification
                else -> throw MalformedPacketException(
                    "Invalid MQTT Control Packet Type: $packetValue Should be in range between 0 and 15 inclusive",
                )
            }
        }
    }
}
