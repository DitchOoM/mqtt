package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * Marker interface for an MQTT PUBLISH Control Packet (MQTT 3.1.1 §3.3 / MQTT 5 §3.3).
 *
 * Protocol-version concrete types ([com.ditchoom.mqtt3.controlpacket.PublishMessageV4] and
 * [com.ditchoom.mqtt5.controlpacket.PublishMessageV5]) are generic on the payload type `P`.
 * The payload's wire encoding/decoding is supplied at the call site via [com.ditchoom.buffer.codec.Codec].
 */
interface PublishMessage : ControlPacket {
    val topic: TopicName
    val qualityOfService: QualityOfService
    val dup: Boolean
    val retain: Boolean
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    /**
     * The expected acknowledgement response for QoS 1 / QoS 2 incoming PUBLISH messages.
     * Returns `null` for QoS 0.
     */
    fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): ControlPacket?

    /**
     * If this message was sent once and is being re-sent, returns a copy with `dup = true`.
     * For QoS 0 always returns this (dup must be false).
     */
    fun setDupFlagNewPubMessage(): PublishMessage

    /**
     * Allocate a fresh packet identifier if needed (QoS > 0); otherwise returns this.
     */
    fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage

    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 3
    }
}
