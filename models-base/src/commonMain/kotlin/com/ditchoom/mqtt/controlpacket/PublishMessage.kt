package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * Marker interface for an MQTT PUBLISH Control Packet (MQTT 3.1.1 §3.3 / MQTT 5 §3.3).
 *
 * Concrete types ([com.ditchoom.mqtt3.controlpacket.PublishMessageV4] and the sealed-tree
 * `ControlPacketV5.Publish<P>`) carry the payload as a typed `P : Payload`. Send-side
 * encoding through the consumer's `Codec<P>` happens eagerly at `MqttClient.publish<P>`;
 * receive-side decoding through the registered per-topic codec happens at the
 * `MqttCodec.decode` layer (zero-copy when the codec is Pattern #1). Subscribers receive
 * the already-typed payload via the concrete subtype's `payload` field — there is no
 * canonical "raw bytes" view to expose at the interface level.
 *
 * Consumers who genuinely want bytes back register `OpaquePublishPayloadCodec` for the
 * topic and access `(publish.payload as OpaquePublishPayload).handle.asReadBuffer()`.
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
