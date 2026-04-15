package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * Wire model for a single subscription entry: topic filter + requested QoS byte.
 */
@ProtocolMessage
data class SubscriptionEntry(
    @LengthPrefixed val filter: String,
    val qos: UByte,
) : ISubscription {
    override val topicFilter: TopicFilter get() = TopicFilter.fromOrThrow(filter)
    override val maximumQos: QualityOfService
        get() =
            QualityOfService.fromBooleans(
                qos.toInt().shr(1) and 1 == 1,
                qos.toInt() and 1 == 1,
            )
}

/**
 * 3.8 SUBSCRIBE - Subscribe request
 *
 * The SUBSCRIBE packet is sent from the Client to the Server to create one or more Subscriptions. Each Subscription
 * registers a Client's interest in one or more Topics. The Server sends PUBLISH packets to the Client to forward
 * Application Messages that were published to Topics that match these Subscriptions. The SUBSCRIBE packet also
 * specifies (for each Subscription) the maximum QoS with which the Server can send Application Messages to the Client.
 *
 * Bits 3,2,1 and 0 of the Fixed Header of the SUBSCRIBE packet are reserved and MUST be set to 0,0,1 and 0
 * respectively. The Server MUST treat any other value as malformed and close the Network Connection [MQTT-3.8.1-1].
 */
@ProtocolMessage
data class SubscribeRequest(
    val packetId: UShort,
    @RemainingBytes val entries: List<SubscriptionEntry>,
) : ControlPacketV4,
    ISubscribeRequest {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val subscriptions: Set<ISubscription> get() = entries.toSet()
    override val controlPacketValue: Byte get() = ISubscribeRequest.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    constructor(packetIdentifier: Int, subscriptions: Set<ISubscription>) :
        this(
            packetIdentifier.toUShort(),
            subscriptions
                .sortedBy { it.topicFilter.toString() }
                .map { SubscriptionEntry(it.topicFilter.toString(), it.maximumQos.integerValue.toUByte()) },
        )

    constructor(packetIdentifier: UShort, topic: TopicFilter, qos: QualityOfService) :
        this(
            packetIdentifier,
            listOf(SubscriptionEntry(topic.toString(), qos.integerValue.toUByte())),
        )

    constructor(packetIdentifier: UShort, topic: String, qos: QualityOfService) :
        this(
            packetIdentifier,
            listOf(SubscriptionEntry(topic, qos.integerValue.toUByte())),
        )

    constructor(packetIdentifier: UShort, topics: List<TopicFilter>, qos: List<QualityOfService>) :
        this(
            packetIdentifier.toInt(),
            subscriptions = Subscription.from(topics, qos),
        )

    constructor(packetIdentifier: Int, topicsQosMap: Map<TopicFilter, QualityOfService>) :
        this(
            packetIdentifier,
            subscriptions = Subscription.from(topicsQosMap.keys.toList(), topicsQosMap.values.toList()),
        )

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest = copy(packetId = packetIdentifier.toUShort())

    override fun encodeBody(writeBuffer: WriteBuffer) = SubscribeRequestCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES + entries.sumOf { it.filter.utf8Length() + UShort.SIZE_BYTES + Byte.SIZE_BYTES }

    override fun expectedResponse(): SubscribeAcknowledgement {
        val returnCodes =
            entries.map {
                when (it.maximumQos) {
                    AT_MOST_ONCE -> ReasonCode.GRANTED_QOS_0
                    AT_LEAST_ONCE -> ReasonCode.GRANTED_QOS_1
                    EXACTLY_ONCE -> ReasonCode.GRANTED_QOS_2
                }
            }
        return SubscribeAcknowledgement(packetId.toInt(), returnCodes)
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remaining: Int,
        ): SubscribeRequest {
            val sliced = buffer.readBytes(remaining)
            return SubscribeRequestCodec.decode(sliced)
        }
    }
}

/**
 * Legacy helper for constructing subscription sets from lists.
 * Used by convenience constructors.
 */
data class Subscription(
    override val topicFilter: TopicFilter,
    override val maximumQos: QualityOfService = AT_LEAST_ONCE,
) : ISubscription {
    companion object {
        fun from(
            topics: List<TopicFilter>,
            qos: List<QualityOfService>,
        ): Set<ISubscription> {
            if (topics.size != qos.size) {
                throw ProtocolError(
                    "[MQTT-3.8.3-3] The payload of a SUBSCRIBE packet MUST contain at least one Topic Filter / QoS pair. A SUBSCRIBE packet with no payload is a protocol violation.",
                )
            }
            val subscriptions = mutableSetOf<ISubscription>()
            topics.forEachIndexed { index, topic ->
                subscriptions += Subscription(topic, qos[index])
            }
            return subscriptions
        }

        fun fromOrThrow(
            topics: List<String>,
            qos: List<QualityOfService>,
        ): Set<ISubscription> {
            if (topics.size != qos.size) {
                throw ProtocolError(
                    "[MQTT-3.8.3-3] The payload of a SUBSCRIBE packet MUST contain at least one Topic Filter / QoS pair. A SUBSCRIBE packet with no payload is a protocol violation.",
                )
            }
            val subscriptions = mutableSetOf<ISubscription>()
            topics.forEachIndexed { index, topic ->
                subscriptions += Subscription(TopicFilter.fromOrThrow(topic), qos[index])
            }
            return subscriptions
        }
    }
}
