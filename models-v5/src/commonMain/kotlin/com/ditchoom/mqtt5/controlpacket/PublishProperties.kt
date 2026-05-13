package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readBufferToOwnedBytes

/**
 * Typed view of PUBLISH variable-header properties (§3.3.2.3). Parallels
 * [ConnectProperties] / [ConnAckProperties]; converts between a `List<MqttProperty>` wire
 * form and named typed accessors.
 */
data class PublishProperties(
    val payloadFormatIndicator: Boolean = false,
    val messageExpiryInterval: Long? = null,
    val topicAlias: Int? = null,
    val responseTopic: TopicName? = null,
    val correlationData: ReadBuffer? = null,
    val userProperty: List<Pair<String, String>> = emptyList(),
    val subscriptionIdentifier: Set<Long> = emptySet(),
    val contentType: String? = null,
) {
    init {
        if (topicAlias == 0) {
            throw ProtocolError(
                "Topic Alias not permitted to be set to 0:" +
                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413",
            )
        }
    }

    val props: List<MqttProperty> by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            if (payloadFormatIndicator) add(PayloadFormatIndicator(isUtf8 = payloadFormatIndicator))
            if (messageExpiryInterval != null) add(MessageExpiryInterval(seconds = messageExpiryInterval.toUInt()))
            if (topicAlias != null) add(TopicAlias(value = topicAlias.toUShort()))
            if (responseTopic != null) add(ResponseTopic(value = responseTopic.toString()))
            if (correlationData != null) {
                correlationData.position(0)
                add(CorrelationData(value = readBufferToOwnedBytes(correlationData)))
            }
            for (kv in userProperty) add(UserProperty(key = kv.first, value = kv.second))
            for (sub in subscriptionIdentifier) add(SubscriptionIdentifier(value = sub.toUInt()))
            if (contentType != null) add(ContentType(value = contentType))
        }
    }

    companion object {
        fun from(keyValuePairs: Collection<MqttProperty>?): PublishProperties {
            val p = PropertyExtractor(keyValuePairs, "PUBLISH")
            val payloadFormatIndicator = p.single<PayloadFormatIndicator>()?.isUtf8 ?: false
            val messageExpiryInterval = p.single<MessageExpiryInterval>()?.seconds?.toLong()
            val topicAlias =
                p
                    .single<TopicAlias>()
                    ?.also {
                        if (it.value == 0.toUShort()) {
                            throw ProtocolError(
                                "Topic Alias not permitted to be set to 0:" +
                                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413",
                            )
                        }
                    }?.value
                    ?.toInt()
            val responseTopic = p.single<ResponseTopic>()?.let { TopicName.fromOrThrow(it.value) }
            val correlationData = p.single<CorrelationData>()?.value?.asReadBuffer()
            val userProperty = p.list<UserProperty>().map { it.key to it.value }
            val subscriptionIdentifier =
                p.list<SubscriptionIdentifier>().mapTo(LinkedHashSet()) {
                    if (it.value == 0u) {
                        throw ProtocolError(
                            "Subscription Identifier not permitted to be set to 0:" +
                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477417",
                        )
                    }
                    it.value.toLong()
                }
            val contentType = p.single<ContentType>()?.value
            p.rejectUnknown()
            return PublishProperties(
                payloadFormatIndicator,
                messageExpiryInterval,
                topicAlias,
                responseTopic,
                correlationData,
                userProperty,
                subscriptionIdentifier,
                contentType,
            )
        }
    }
}
