package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MaximumPacketSize
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReceiveMaximum
import com.ditchoom.mqtt5.controlpacket.properties.RequestProblemInformation
import com.ditchoom.mqtt5.controlpacket.properties.RequestResponseInformation
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.TopicAliasMaximum
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.properties.readBufferToOwnedBytes

/**
 * Typed view of CONNECT variable-header properties (§3.1.2.11).
 */
data class ConnectProperties(
    val sessionExpiryIntervalSeconds: ULong? = null,
    val receiveMaximum: Int? = null,
    val maximumPacketSize: ULong? = null,
    val topicAliasMaximum: Int? = null,
    val requestResponseInformation: Boolean? = null,
    val requestProblemInformation: Boolean? = null,
    val userProperty: List<Pair<String, String>> = emptyList(),
    val authentication: Authentication? = null,
) {
    val props: List<MqttProperty> =
        buildList {
            if (sessionExpiryIntervalSeconds != null) {
                add(SessionExpiryInterval(seconds = sessionExpiryIntervalSeconds.toUInt()))
            }
            if (receiveMaximum != null) add(ReceiveMaximum(max = receiveMaximum.toUShort()))
            if (maximumPacketSize != null) add(MaximumPacketSize(bytes = maximumPacketSize.toUInt()))
            if (topicAliasMaximum != null) add(TopicAliasMaximum(max = topicAliasMaximum.toUShort()))
            if (requestResponseInformation != null) {
                add(RequestResponseInformation(enabled = requestResponseInformation))
            }
            if (requestProblemInformation != null) {
                add(RequestProblemInformation(enabled = requestProblemInformation))
            }
            for ((k, v) in userProperty) add(UserProperty(key = k, value = v))
            if (authentication != null) {
                add(AuthenticationMethod(value = authentication.method))
                authentication.data.position(0)
                add(AuthenticationData(value = readBufferToOwnedBytes(authentication.data)))
            }
        }

    fun size(): Int = mqttPropertiesSize(props)

    companion object {
        fun from(keyValuePairs: Collection<MqttProperty>?): ConnectProperties {
            val p = PropertyExtractor(keyValuePairs, "CONNECT")
            val sessionExpiry = p.single<SessionExpiryInterval>()?.seconds?.toULong()
            val receiveMax =
                p
                    .single<ReceiveMaximum>()
                    ?.also {
                        if (it.max == 0.toUShort()) {
                            throw ProtocolError(
                                "Receive Maximum cannot be set to 0 see: " +
                                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477349",
                            )
                        }
                    }?.max
                    ?.toInt()
            val maximumPacketSize =
                p
                    .single<MaximumPacketSize>()
                    ?.also {
                        if (it.bytes == 0u) {
                            throw ProtocolError(
                                "Maximum Packet Size cannot be set to 0 see: " +
                                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477350",
                            )
                        }
                    }?.bytes
                    ?.toULong()
            val topicAliasMaximum = p.single<TopicAliasMaximum>()?.max?.toInt()
            val requestResponseInformation = p.single<RequestResponseInformation>()?.enabled
            val requestProblemInformation = p.single<RequestProblemInformation>()?.enabled
            val userProperty = p.list<UserProperty>().map { it.key to it.value }
            val authMethod = p.single<AuthenticationMethod>()?.value
            val authData = p.single<AuthenticationData>()?.value?.asReadBuffer()
            p.rejectUnknown()
            val auth =
                if (authMethod != null && authData != null) {
                    Authentication(authMethod, authData)
                } else {
                    null
                }
            return ConnectProperties(
                sessionExpiry,
                receiveMax,
                maximumPacketSize,
                topicAliasMaximum,
                requestResponseInformation,
                requestProblemInformation,
                userProperty,
                auth,
            )
        }
    }
}

/**
 * Typed view of CONNECT will-properties (§3.1.3.2). Conditional on `connectFlags.willFlag`.
 */
data class ConnectWillProperties(
    val willDelayIntervalSeconds: Long = 0L,
    val payloadFormatIndicator: Boolean = false,
    val messageExpiryIntervalSeconds: Long? = null,
    val contentType: String? = null,
    val responseTopic: TopicName? = null,
    val correlationData: ReadBuffer? = null,
    val userProperty: List<Pair<String, String>> = emptyList(),
) {
    val props: List<MqttProperty> =
        buildList {
            if (willDelayIntervalSeconds != 0L) {
                add(WillDelayInterval(seconds = willDelayIntervalSeconds.toUInt()))
            }
            if (payloadFormatIndicator) add(PayloadFormatIndicator(isUtf8 = payloadFormatIndicator))
            if (messageExpiryIntervalSeconds != null) {
                add(MessageExpiryInterval(seconds = messageExpiryIntervalSeconds.toUInt()))
            }
            if (contentType != null) add(ContentType(value = contentType))
            if (responseTopic != null) add(ResponseTopic(value = responseTopic.toString()))
            if (correlationData != null) {
                correlationData.position(0)
                add(CorrelationData(value = readBufferToOwnedBytes(correlationData)))
            }
            for ((k, v) in userProperty) add(UserProperty(key = k, value = v))
        }

    fun size(): Int = mqttPropertiesSize(props)

    companion object {
        fun from(properties: Collection<MqttProperty>?): ConnectWillProperties {
            if (properties == null) return ConnectWillProperties()
            val p = PropertyExtractor(properties, "CONNECT Will")
            val willDelayIntervalSeconds = p.single<WillDelayInterval>()?.seconds?.toLong() ?: 0L
            val payloadFormatIndicator = p.single<PayloadFormatIndicator>()?.isUtf8 ?: false
            val messageExpiryIntervalSeconds = p.single<MessageExpiryInterval>()?.seconds?.toLong()
            val contentType = p.single<ContentType>()?.value
            val responseTopic = p.single<ResponseTopic>()?.let { TopicName.fromOrThrow(it.value) }
            val correlationData = p.single<CorrelationData>()?.value?.asReadBuffer()
            val userProperty = p.list<UserProperty>().map { it.key to it.value }
            p.rejectUnknown()
            return ConnectWillProperties(
                willDelayIntervalSeconds,
                payloadFormatIndicator,
                messageExpiryIntervalSeconds,
                contentType,
                responseTopic,
                correlationData,
                userProperty,
            )
        }
    }
}
