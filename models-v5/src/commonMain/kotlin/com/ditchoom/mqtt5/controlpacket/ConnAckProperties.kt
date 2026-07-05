package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BAD_AUTHENTICATION_METHOD
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BAD_USER_NAME_OR_PASSWORD
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BANNED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CLIENT_IDENTIFIER_NOT_VALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONNECTION_RATE_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MALFORMED_PACKET
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_TOO_LARGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PROTOCOL_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QOS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RETAIN_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_BUSY
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_MOVED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_UNAVAILABLE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSUPPORTED_PROTOCOL_VERSION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.USE_ANOTHER_SERVER
import com.ditchoom.mqtt5.controlpacket.properties.AssignedClientIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.MaximumPacketSize
import com.ditchoom.mqtt5.controlpacket.properties.MaximumQos
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ReceiveMaximum
import com.ditchoom.mqtt5.controlpacket.properties.ResponseInformation
import com.ditchoom.mqtt5.controlpacket.properties.RetainAvailable
import com.ditchoom.mqtt5.controlpacket.properties.ServerKeepAlive
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.SharedSubscriptionAvailable
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifierAvailable
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.TopicAliasMaximum
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WildcardSubscriptionAvailable
import com.ditchoom.mqtt5.controlpacket.properties.readBufferToOwnedBytes

/**
 * Typed view of a CONNACK property bag. Spec §3.2.2.3.
 *
 * Construct from typed fields and call [props] for the wire shape; read from a
 * decoded `Collection<MqttProperty>` via [from] for the typed view.
 */
data class ConnAckProperties(
    val sessionExpiryIntervalSeconds: ULong? = null,
    val receiveMaximum: Int = UShort.MAX_VALUE.toInt(),
    val maximumQos: QualityOfService = QualityOfService.EXACTLY_ONCE,
    val retainAvailable: Boolean = true,
    val maximumPacketSize: ULong? = null,
    val assignedClientIdentifier: String? = null,
    val topicAliasMaximum: Int = 0,
    val reasonString: String? = null,
    val userProperty: List<Pair<String, String>> = emptyList(),
    val supportsWildcardSubscriptions: Boolean = true,
    val subscriptionIdentifiersAvailable: Boolean = true,
    val sharedSubscriptionAvailable: Boolean = true,
    val serverKeepAlive: Int? = null,
    val responseInformation: String? = null,
    val serverReference: String? = null,
    val authentication: Authentication? = null,
) {
    val props: List<MqttProperty> =
        buildList {
            if (sessionExpiryIntervalSeconds != null) {
                add(SessionExpiryInterval(seconds = sessionExpiryIntervalSeconds.toUInt()))
            }
            if (receiveMaximum != UShort.MAX_VALUE.toInt()) {
                add(ReceiveMaximum(max = receiveMaximum.toUShort()))
            }
            if (maximumQos != QualityOfService.EXACTLY_ONCE) {
                add(MaximumQos(qos1Allowed = maximumQos != QualityOfService.AT_MOST_ONCE))
            }
            if (!retainAvailable) add(RetainAvailable(supported = retainAvailable))
            if (maximumPacketSize != null) add(MaximumPacketSize(bytes = maximumPacketSize.toUInt()))
            if (assignedClientIdentifier != null) add(AssignedClientIdentifier(value = assignedClientIdentifier))
            if (topicAliasMaximum != 0) add(TopicAliasMaximum(max = topicAliasMaximum.toUShort()))
            if (reasonString != null) add(ReasonString(value = reasonString))
            for ((k, v) in userProperty) add(UserProperty(key = k, value = v))
            if (!supportsWildcardSubscriptions) {
                add(WildcardSubscriptionAvailable(supported = supportsWildcardSubscriptions))
            }
            if (!subscriptionIdentifiersAvailable) {
                add(SubscriptionIdentifierAvailable(supported = subscriptionIdentifiersAvailable))
            }
            if (!sharedSubscriptionAvailable) {
                add(SharedSubscriptionAvailable(supported = sharedSubscriptionAvailable))
            }
            if (serverKeepAlive != null) add(ServerKeepAlive(seconds = serverKeepAlive.toUShort()))
            if (responseInformation != null) add(ResponseInformation(value = responseInformation))
            if (serverReference != null) add(ServerReference(value = serverReference))
            if (authentication != null) {
                add(AuthenticationMethod(value = authentication.method))
                authentication.data.position(0)
                add(AuthenticationData(value = readBufferToOwnedBytes(authentication.data)))
            }
        }

    companion object {
        fun from(keyValuePairs: Collection<MqttProperty>?): ConnAckProperties {
            val p = PropertyExtractor(keyValuePairs, "CONNACK")
            val sessionExpiry = p.single<SessionExpiryInterval>()?.seconds?.toULong()
            val receiveMax =
                p
                    .single<ReceiveMaximum>()
                    ?.also {
                        if (it.max == 0.toUShort()) {
                            throw ProtocolError(
                                "Receive Maximum cannot be set to 0 see: " +
                                    "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477383",
                            )
                        }
                    }?.max
                    ?.toInt()
            val maximumQos =
                p.single<MaximumQos>()?.let {
                    if (it.qos1Allowed) QualityOfService.AT_LEAST_ONCE else QualityOfService.AT_MOST_ONCE
                }
            val retainAvailable = p.single<RetainAvailable>()?.supported
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
            val assignedClientId = p.single<AssignedClientIdentifier>()?.value
            val topicAlias = (p.single<TopicAliasMaximum>()?.max ?: p.single<TopicAlias>()?.value)?.toInt()
            val reasonString = p.single<ReasonString>()?.value
            val userProperty = p.list<UserProperty>().map { it.key to it.value }
            val wildcardSub = p.single<WildcardSubscriptionAvailable>()?.supported
            val subIdAvailable = p.single<SubscriptionIdentifierAvailable>()?.supported
            val sharedSub = p.single<SharedSubscriptionAvailable>()?.supported
            val serverKeepAlive = p.single<ServerKeepAlive>()?.seconds?.toInt()
            val responseInfo = p.single<ResponseInformation>()?.value
            val serverRef = p.single<ServerReference>()?.value
            val authMethod = p.single<AuthenticationMethod>()?.value
            val authData = p.single<AuthenticationData>()?.value?.asReadBuffer()
            p.rejectUnknown()
            val auth =
                if (authMethod != null && authData != null) {
                    Authentication(authMethod, authData)
                } else {
                    null
                }
            return ConnAckProperties(
                sessionExpiry,
                receiveMax ?: UShort.MAX_VALUE.toInt(),
                maximumQos ?: QualityOfService.EXACTLY_ONCE,
                retainAvailable ?: true,
                maximumPacketSize,
                assignedClientId,
                topicAlias ?: 0,
                reasonString,
                userProperty,
                wildcardSub ?: true,
                subIdAvailable ?: true,
                sharedSub ?: true,
                serverKeepAlive,
                responseInfo,
                serverRef,
                auth,
            )
        }
    }
}

// Spec §3.2.2.2 valid Connect Reason Codes.
val connackConnectReason: Map<UByte, ReasonCode> by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        SUCCESS.byte to SUCCESS,
        UNSPECIFIED_ERROR.byte to UNSPECIFIED_ERROR,
        MALFORMED_PACKET.byte to MALFORMED_PACKET,
        PROTOCOL_ERROR.byte to PROTOCOL_ERROR,
        IMPLEMENTATION_SPECIFIC_ERROR.byte to IMPLEMENTATION_SPECIFIC_ERROR,
        UNSUPPORTED_PROTOCOL_VERSION.byte to UNSUPPORTED_PROTOCOL_VERSION,
        CLIENT_IDENTIFIER_NOT_VALID.byte to CLIENT_IDENTIFIER_NOT_VALID,
        BAD_USER_NAME_OR_PASSWORD.byte to BAD_USER_NAME_OR_PASSWORD,
        NOT_AUTHORIZED.byte to NOT_AUTHORIZED,
        SERVER_UNAVAILABLE.byte to SERVER_UNAVAILABLE,
        SERVER_BUSY.byte to SERVER_BUSY,
        BANNED.byte to BANNED,
        BAD_AUTHENTICATION_METHOD.byte to BAD_AUTHENTICATION_METHOD,
        TOPIC_NAME_INVALID.byte to TOPIC_NAME_INVALID,
        PACKET_TOO_LARGE.byte to PACKET_TOO_LARGE,
        QUOTA_EXCEEDED.byte to QUOTA_EXCEEDED,
        PAYLOAD_FORMAT_INVALID.byte to PAYLOAD_FORMAT_INVALID,
        RETAIN_NOT_SUPPORTED.byte to RETAIN_NOT_SUPPORTED,
        QOS_NOT_SUPPORTED.byte to QOS_NOT_SUPPORTED,
        USE_ANOTHER_SERVER.byte to USE_ANOTHER_SERVER,
        SERVER_MOVED.byte to SERVER_MOVED,
        CONNECTION_RATE_EXCEEDED.byte to CONNECTION_RATE_EXCEEDED,
    )
}
