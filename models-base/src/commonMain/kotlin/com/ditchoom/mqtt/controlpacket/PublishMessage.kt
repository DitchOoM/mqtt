package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * Marker interface for an MQTT PUBLISH Control Packet (MQTT 3.1.1 §3.3 / MQTT 5 §3.3).
 *
 * Concrete types ([com.ditchoom.mqtt3.controlpacket.PublishMessageV4] and the
 * sealed-tree `V5Packet.Publish<P>`) carry the payload as a [ReadBuffer]. Typed payload encoding
 * happens eagerly at the publish API boundary; on the dispatch side, subscribers
 * decode the wire slice through their own `ReadBuffer.() -> P` lambda.
 */
interface PublishMessage : ControlPacket {
    val topic: TopicName
    val qualityOfService: QualityOfService
    val dup: Boolean
    val retain: Boolean
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    /**
     * Wire-bytes payload (zero-copy slice). Empty payloads return an empty buffer (never null
     * for the v4 [PublishMessageV4] impl). Returns null only for the
     * sealed-tree `V5Packet.Publish<P>` variant when `P` is not a [ReadBuffer] — that variant
     * exists for the codec processor's typed-payload pipeline and is not used by the
     * eager-encode publish API.
     */
    fun rawPayload(): ReadBuffer?

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
