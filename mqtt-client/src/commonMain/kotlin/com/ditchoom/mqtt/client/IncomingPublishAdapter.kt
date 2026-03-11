package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IncomingPublish
import com.ditchoom.mqtt.controlpacket.IncomingPublishV5
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName

/**
 * Adapts an [IPublishMessage] to [IncomingPublish] or [IncomingPublishV5].
 *
 * Uses [from] factory to produce the correct subtype based on the concrete
 * publish message class (v4 vs v5).
 */
internal class IncomingPublishV4Adapter(
    override val topic: TopicName,
    override val qos: QualityOfService,
    override val dup: Boolean,
    override val retain: Boolean,
    override val payload: ReadBuffer?,
) : IncomingPublish

internal class IncomingPublishV5Adapter(
    override val topic: TopicName,
    override val qos: QualityOfService,
    override val dup: Boolean,
    override val retain: Boolean,
    override val payload: ReadBuffer?,
    override val payloadFormatIndicator: Boolean,
    override val messageExpiryInterval: Long?,
    override val topicAlias: Int?,
    override val responseTopic: TopicName?,
    override val correlationData: ReadBuffer?,
    override val userProperty: List<Pair<String, String>>,
    override val subscriptionIdentifier: Set<Long>,
    override val contentType: String?,
) : IncomingPublishV5

/**
 * Wraps an [IncomingPublish] with a [ScopedReadBuffer] payload.
 * Preserves the V5 smart-cast: if the delegate is [IncomingPublishV5],
 * the wrapper also implements [IncomingPublishV5].
 */
internal fun ScopedIncomingPublish(delegate: IncomingPublish, scopedPayload: ScopedReadBuffer): IncomingPublish =
    when (delegate) {
        is IncomingPublishV5 -> ScopedIncomingPublishV5(delegate, scopedPayload)
        else -> ScopedIncomingPublishV4(delegate, scopedPayload)
    }

private class ScopedIncomingPublishV4(
    private val delegate: IncomingPublish,
    override val payload: ReadBuffer?,
) : IncomingPublish {
    override val topic: TopicName get() = delegate.topic
    override val qos: QualityOfService get() = delegate.qos
    override val dup: Boolean get() = delegate.dup
    override val retain: Boolean get() = delegate.retain
}

private class ScopedIncomingPublishV5(
    private val delegate: IncomingPublishV5,
    override val payload: ReadBuffer?,
) : IncomingPublishV5 {
    override val topic: TopicName get() = delegate.topic
    override val qos: QualityOfService get() = delegate.qos
    override val dup: Boolean get() = delegate.dup
    override val retain: Boolean get() = delegate.retain
    override val payloadFormatIndicator: Boolean get() = delegate.payloadFormatIndicator
    override val messageExpiryInterval: Long? get() = delegate.messageExpiryInterval
    override val topicAlias: Int? get() = delegate.topicAlias
    override val responseTopic: TopicName? get() = delegate.responseTopic
    override val correlationData: ReadBuffer? get() = delegate.correlationData
    override val userProperty: List<Pair<String, String>> get() = delegate.userProperty
    override val subscriptionIdentifier: Set<Long> get() = delegate.subscriptionIdentifier
    override val contentType: String? get() = delegate.contentType
}

/**
 * Creates an [IncomingPublish] (or [IncomingPublishV5]) from an [IPublishMessage].
 *
 * Extracts dup/retain from the concrete V4 or V5 FixedHeader.
 * Returns [IncomingPublishV5] for V5 messages so callers can smart-cast.
 */
internal fun IPublishMessage.toIncomingPublish(): IncomingPublish {
    // Try V5 first (more specific)
    val v5 = this as? com.ditchoom.mqtt5.controlpacket.PublishMessage
    if (v5 != null) {
        val props = v5.variable.properties
        return IncomingPublishV5Adapter(
            topic = v5.topic,
            qos = v5.qualityOfService,
            dup = v5.fixed.dup,
            retain = v5.fixed.retain,
            payload = v5.payload,
            payloadFormatIndicator = props.payloadFormatIndicator,
            messageExpiryInterval = props.messageExpiryInterval,
            topicAlias = props.topicAlias,
            responseTopic = props.responseTopic,
            correlationData = props.correlationData,
            userProperty = props.userProperty,
            subscriptionIdentifier = props.subscriptionIdentifier,
            contentType = props.contentType,
        )
    }

    // V4
    val v4 = this as? com.ditchoom.mqtt3.controlpacket.PublishMessage
    if (v4 != null) {
        return IncomingPublishV4Adapter(
            topic = v4.topic,
            qos = v4.qualityOfService,
            dup = v4.fixed.dup,
            retain = v4.fixed.retain,
            payload = v4.payload,
        )
    }

    // Fallback for unknown implementations (shouldn't happen in practice)
    return IncomingPublishV4Adapter(
        topic = topic,
        qos = qualityOfService,
        dup = false,
        retain = false,
        payload = payload,
    )
}
