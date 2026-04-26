@file:OptIn(ExperimentalJsExport::class)
@file:Suppress("NON_EXPORTABLE_TYPE")

package com.ditchoom.mqtt5.persistence

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.payloadAsByteArrayOrNull
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt5.controlpacket.ConnectProperties
import com.ditchoom.mqtt5.controlpacket.ConnectWillProperties
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishMessageV5
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import org.khronos.webgl.Int8Array
import kotlin.time.Duration.Companion.milliseconds

@JsExport
data class PersistableUserProperty(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("incoming")
    val incoming: Int,
    @JsName("packetId")
    val packetId: Int,
    @JsName("key")
    val key: String,
    @JsName("value")
    val value: String,
)

@JsExport
data class PersistableQos2Message(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int,
    @JsName("type")
    val type: Byte,
    @JsName("incoming")
    val incoming: Int,
    @JsName("reasonCode")
    val reasonCode: Int,
    @JsName("reasonString")
    val reasonString: String?,
)

@JsExport
data class PersistableUnsubscribe(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int,
) {
    @JsName("constuct")
    constructor(brokerId: Int, unsub: UnsubscribeRequest) :
        this(brokerId, unsub.packetIdentifier)
}

// fun toUnsubscribe(p: PersistableUnsubscribe) =
//    UnsubscribeRequest(p.brokerId, p.packetId)//, p.topic.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet())

@JsExport
data class PersistableSubscribe(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int,
    @JsName("reasonString")
    val reasonString: String?,
)

@JsExport
data class PersistableSubscription(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("topicFilter")
    val topicFilter: String,
    @JsName("subscribeId")
    val subscribeId: Int,
    @JsName("unsubscribeId")
    val unsubscribeId: Int,
    @JsName("qos")
    val qos: Byte,
    @JsName("noLocal")
    val noLocal: Boolean,
    @JsName("retainAsPublished")
    val retainAsPublished: Boolean,
    @JsName("retainHandling")
    val retainHandling: Int,
) {
    @JsName("construct")
    constructor(brokerId: Int, subscribeId: Int, sub: Subscription) :
        this(
            brokerId,
            sub.topicFilter.toString(),
            subscribeId,
            -1,
            sub.maximumQos.integerValue,
            sub.noLocal,
            sub.retainAsPublished,
            sub.retainHandling.value.toInt(),
        )
}

fun toSubscription(s: PersistableSubscription) =
    Subscription(
        TopicFilter.fromOrThrow(s.topicFilter),
        s.qos.toQos(),
        s.noLocal,
        s.retainAsPublished,
        when (s.retainHandling) {
            1 -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
            2 -> ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
            else -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
        },
    )

@JsExport
data class PersistablePublishMessage(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("incoming")
    val incoming: Int,
    @JsName("dup")
    val dup: Boolean,
    @JsName("qos")
    val qos: Byte,
    @JsName("retain")
    val retain: Boolean,
    @JsName("topicName")
    val topicName: String,
    @JsName("packetId")
    val packetId: Int,
    @JsName("payloadFormatIndicator")
    val payloadFormatIndicator: Int,
    @JsName("messageExpiryInterval")
    val messageExpiryInterval: String?,
    @JsName("topic_alias")
    val topicAlias: Int?,
    @JsName("responseTopic")
    val responseTopic: String?,
    @JsName("correlationData")
    val correlationData: Int8Array?,
    @JsName("subscriptionIdentifier")
    val subscriptionIdentifier: String?,
    @JsName("contentType")
    val contentType: String?,
    @JsName("payload")
    val payload: Int8Array?,
    @JsName("state")
    val state: Int = 0,
) {
    @JsName("construct")
    constructor(brokerId: Int, incoming: Boolean, pub: PublishMessageV5) : this(
        brokerId,
        if (incoming) 1 else 0,
        pub.dup,
        pub.qualityOfService.integerValue,
        pub.retain,
        pub.topic.toString(),
        pub.packetIdentifier,
        pub.properties.payloadFormatIndicator.toLong().toInt(),
        pub.properties.messageExpiryInterval?.toString(),
        pub.properties.topicAlias,
        pub.properties.responseTopic?.toString(),
        pub.properties.correlationData?.let { (it as JsBuffer).buffer },
        if (pub.properties.subscriptionIdentifier.isNotEmpty()) {
            pub.properties.subscriptionIdentifier.joinToString(", ")
        } else {
            null
        },
        pub.properties.contentType,
        pub.payloadAsByteArrayOrNull()?.unsafeCast<Int8Array>(),
        0,
    )
}

fun toPub(
    p: PersistablePublishMessage,
    userProperty: List<Pair<String, String>>,
): PublishMessage =
    PublishMessageV5.ofRaw(
        topic = TopicName.fromOrThrow(p.topicName),
        qos = p.qos.toQos(),
        payload =
            p.payload?.let {
                JsBuffer(it).also { buf ->
                    buf.position(it.length)
                    buf.setLimit(it.length)
                }
            }?.also { it.resetForRead() },
        dup = p.dup,
        retain = p.retain,
        packetIdentifier = p.packetId,
        properties =
            PublishMessageV5.Properties(
                p.payloadFormatIndicator == 1,
                p.messageExpiryInterval?.toLong(),
                p.topicAlias,
                p.responseTopic?.let { TopicName.fromOrThrow(it) },
                p.correlationData
                    ?.let {
                        JsBuffer(it).also { buf ->
                            buf.position(it.length)
                            buf.setLimit(it.length)
                        }
                    }?.also { it.resetForRead() },
                userProperty,
                p.subscriptionIdentifier
                    ?.split(", ")
                    ?.map { it.toLong() }
                    ?.toSet() ?: emptySet(),
                p.contentType,
            ),
    )

@JsExport
data class PersistableBroker(
    @JsName("id")
    val id: Int,
    @JsName("connectionOptions")
    val connectionOptions: Array<PersistableSocketConnection>,
    @JsName("connectionRequest")
    val connectionRequest: PersistableConnectionRequest,
)

@JsExport
data class PersistableSocketConnection(
    @JsName("type")
    val type: String,
    @JsName("host")
    val host: String,
    @JsName("port")
    val port: Int,
    @JsName("tls")
    val tlsEnabled: Boolean,
    @JsName("connectionTimeoutMs")
    val connectionTimeoutMs: String,
    @JsName("readTimeoutMs")
    val readTimeoutMs: String,
    @JsName("writeTimeoutMs")
    val writeTimeoutMs: String,
    @JsName("websocketEndpoint")
    val websocketEndpoint: String?,
    @JsName("websocketProtocols")
    val websocketProtocols: String?,
) {
    companion object {
        fun from(connectionOps: Collection<MqttConnectionOptions>) =
            connectionOps
                .map {
                    when (it) {
                        is MqttConnectionOptions.SocketConnection -> {
                            PersistableSocketConnection(
                                "tcp",
                                it.host,
                                it.port,
                                it.tlsEnabled,
                                it.connectionTimeout.inWholeMilliseconds.toString(),
                                it.readTimeout.inWholeMilliseconds.toString(),
                                it.writeTimeout.inWholeMilliseconds.toString(),
                                null,
                                null,
                            )
                        }

                        is MqttConnectionOptions.WebSocketConnectionOptions -> {
                            PersistableSocketConnection(
                                "websocket",
                                it.host,
                                it.port,
                                it.tlsEnabled,
                                it.connectionTimeout.inWholeMilliseconds.toString(),
                                it.readTimeout.inWholeMilliseconds.toString(),
                                it.writeTimeout.inWholeMilliseconds.toString(),
                                it.websocketEndpoint,
                                it.protocols.joinToString(),
                            )
                        }
                    }
                }.toTypedArray()
    }
}

fun toSocketConnection(a: Any?): MqttConnectionOptions {
    val p = a.asDynamic()
    return if (p.type == "tcp") {
        MqttConnectionOptions.SocketConnection(
            p.host as String,
            p.port.unsafeCast<Int>(),
            tlsEnabled = p.tls.unsafeCast<Boolean>(),
            connectionTimeout = (p.connectionTimeoutMs as String).toLong().milliseconds,
            readTimeout = (p.readTimeoutMs as String).toLong().milliseconds,
            writeTimeout = (p.writeTimeoutMs as String).toLong().milliseconds,
        )
    } else {
        MqttConnectionOptions.WebSocketConnectionOptions(
            host = p.host as String,
            port = p.port as Int,
            tlsEnabled = p.tls as Boolean,
            connectionTimeout = (p.connectionTimeoutMs as String).toLong().milliseconds,
            readTimeout = (p.readTimeoutMs as String).toLong().milliseconds,
            writeTimeout = (p.writeTimeoutMs as String).toLong().milliseconds,
            websocketEndpoint = (p.websocketEndpoint ?: "") as String,
            protocols = (p.websocketProtocols as? String)?.split(", ") ?: emptyList(),
        )
    }
}

@JsExport
data class PersistableConnectionRequest(
    @JsName("protocolName")
    val protocolName: String,
    @JsName("protocolLevel")
    val protocolLevel: Int,
    @JsName("willRetain")
    val willRetain: Boolean,
    @JsName("willQos")
    val willQos: Byte,
    @JsName("willFlag")
    val willFlag: Boolean,
    @JsName("cleanSession")
    val cleanSession: Boolean,
    @JsName("keepAliveSeconds")
    val keepAliveSeconds: Int,
    @JsName("sessionExpiryIntervalSeconds")
    val sessionExpiryIntervalSeconds: String?,
    @JsName("receiveMaximum")
    val receiveMaximum: Int?,
    @JsName("maximumPacketSize")
    val maximumPacketSize: String?,
    @JsName("topicAliasMaximum")
    val topicAliasMaximum: Int?,
    @JsName("requestResponseInformation")
    val requestResponseInformation: Boolean?,
    @JsName("requestProblemInformation")
    val requestProblemInformation: Boolean?,
    @JsName("authMethod")
    val authMethod: String?,
    @JsName("authData")
    val authData: Int8Array?,
    @JsName("clientId")
    val clientId: String,
    @JsName("hasWillProperties")
    val hasWillProperties: Boolean,
    @JsName("willTopic")
    val willTopic: String?,
    @JsName("willPayload")
    val willPayload: Int8Array?,
    @JsName("username")
    val username: String?,
    @JsName("password")
    val password: String?,
    @JsName("willPropertyWillDelayIntervalSeconds")
    val willPropertyWillDelayIntervalSeconds: Int,
    @JsName("willPropertyPayloadFormatIndicator")
    val willPropertyPayloadFormatIndicator: Boolean,
    @JsName("willPropertyMessageExpiryIntervalSeconds")
    val willPropertyMessageExpiryIntervalSeconds: String?,
    @JsName("willPropertyContentType")
    val willPropertyContentType: String?,
    @JsName("willPropertyResponseTopic")
    val willPropertyResponseTopic: String?,
    @JsName("willPropertyCorrelationData")
    val willPropertyCorrelationData: Int8Array?,
) {
    companion object {
        fun from(connectionRequest: ConnectionRequest): PersistableConnectionRequest {
            val flags = connectionRequest.connectFlags
            val typedProps = connectionRequest.typedProperties
            val typedWillProps = connectionRequest.typedWillProperties
            val willEnabled = connectionRequest.will as? WillConfig.Enabled
            return PersistableConnectionRequest(
                connectionRequest.protocolName,
                connectionRequest.protocolLevel.toInt(),
                flags.willRetain,
                willEnabled?.qos?.integerValue ?: 0,
                flags.willFlag,
                flags.cleanStart,
                connectionRequest.keepAlive.toInt(),
                typedProps.sessionExpiryIntervalSeconds?.toString(),
                typedProps.receiveMaximum,
                typedProps.maximumPacketSize?.toString(),
                typedProps.topicAliasMaximum,
                typedProps.requestResponseInformation,
                typedProps.requestProblemInformation,
                typedProps.authentication?.method,
                typedProps.authentication?.data?.let { (it as JsBuffer).buffer },
                connectionRequest.clientId,
                typedWillProps != null,
                willEnabled?.topic?.toString(),
                (willEnabled?.payload as? JsBuffer)?.buffer,
                connectionRequest.userName,
                connectionRequest.password,
                typedWillProps?.willDelayIntervalSeconds?.toInt() ?: 0,
                typedWillProps?.payloadFormatIndicator ?: false,
                typedWillProps?.messageExpiryIntervalSeconds?.toString(),
                typedWillProps?.contentType,
                typedWillProps?.responseTopic?.toString(),
                typedWillProps?.correlationData?.let { (it as JsBuffer).buffer },
            )
        }
    }
}

fun toConnectionRequest(
    a: Any?,
    userProperty: List<Pair<String, String>>,
    willUserProperty: List<Pair<String, String>>,
): ConnectionRequest {
    val p = a.asDynamic()
    val authMethod = p.authMethod as String?
    val authData =
        (p.authData as Int8Array?)?.let {
            JsBuffer(it).also { buf ->
                buf.position(it.length)
                buf.setLimit(it.length)
            }
        }
    val auth =
        if (authMethod != null && authData != null) {
            Authentication(authMethod, authData)
        } else {
            null
        }
    val willPayloadBuffer =
        p.willPayload
            ?.unsafeCast<Int8Array>()
            ?.let {
                JsBuffer(it).also { buf ->
                    buf.position(it.length)
                    buf.setLimit(it.length)
                }
            }
    val willTopic = (p.willTopic as? String)?.let { TopicName.fromOrThrow(it) }
    val willFlag = p.willFlag as Boolean
    val will: WillConfig =
        if (willFlag && willTopic != null && willPayloadBuffer != null) {
            WillConfig.Enabled(
                topic = willTopic,
                payload = willPayloadBuffer,
                qos = (p.willQos as Byte).toQos(),
                retain = p.willRetain as Boolean,
            )
        } else {
            WillConfig.Disabled
        }
    val willProps =
        if (p.hasWillProperties as Boolean) {
            ConnectWillProperties(
                willDelayIntervalSeconds = (p.willPropertyWillDelayIntervalSeconds as Int).toLong(),
                payloadFormatIndicator = p.willPropertyPayloadFormatIndicator as Boolean,
                messageExpiryIntervalSeconds = (p.willPropertyMessageExpiryIntervalSeconds as String?)?.toLong(),
                contentType = p.willPropertyContentType as String?,
                responseTopic = (p.willPropertyResponseTopic as String?)?.let { TopicName.fromOrThrow(it) },
                correlationData =
                    p.willPropertyCorrelationData
                        ?.unsafeCast<Int8Array>()
                        ?.let { JsBuffer(it).also { buf -> buf.setLimit(it.length) } },
                userProperty = willUserProperty,
            )
        } else {
            null
        }
    val variableHeaderProps =
        ConnectProperties(
            sessionExpiryIntervalSeconds = (p.sessionExpiryIntervalSeconds as String?)?.toULong(),
            receiveMaximum = p.receiveMaximum as Int?,
            maximumPacketSize = (p.maximumPacketSize as String?)?.toULong(),
            topicAliasMaximum = p.topicAliasMaximum as Int?,
            requestResponseInformation = p.requestResponseInformation as Boolean?,
            requestProblemInformation = p.requestProblemInformation as Boolean?,
            userProperty = userProperty,
            authentication = auth,
        )
    return ConnectionRequest(
        clientId = p.clientId as String,
        keepAliveSeconds = p.keepAliveSeconds as Int,
        cleanStart = p.cleanSession as Boolean,
        userName = p.username as? String,
        password = p.password as? String,
        will = will,
        protocolName = p.protocolName as String,
        protocolVersion = (p.protocolLevel as Int).toUByte(),
        props = variableHeaderProps,
        willProperties = willProps,
    )
}

fun Byte.toQos(): QualityOfService =
    when (toInt()) {
        1 -> QualityOfService.AT_LEAST_ONCE
        2 -> QualityOfService.EXACTLY_ONCE
        else -> QualityOfService.AT_MOST_ONCE
    }
