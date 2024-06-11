@file:OptIn(ExperimentalJsExport::class)
@file:Suppress("NON_EXPORTABLE_TYPE")

package com.ditchoom.mqtt5.persistence

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishMessage
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
        Topic.fromOrThrow(s.topicFilter, Topic.Type.Filter),
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
) {
    @JsName("construct")
    constructor(brokerId: Int, incoming: Boolean, pub: PublishMessage) : this(
        brokerId,
        if (incoming) 1 else 0,
        pub.fixed.dup,
        pub.fixed.qos.integerValue,
        pub.fixed.retain,
        pub.variable.topicName.toString(),
        pub.variable.packetIdentifier,
        pub.variable.properties.payloadFormatIndicator.toLong().toInt(),
        pub.variable.properties.messageExpiryInterval?.toString(),
        pub.variable.properties.topicAlias,
        pub.variable.properties.responseTopic?.toString(),
        pub.variable.properties.correlationData?.let { (it as JsBuffer).buffer },
        if (pub.variable.properties.subscriptionIdentifier.isNotEmpty()) {
            pub.variable.properties.subscriptionIdentifier.joinToString(", ")
        } else {
            null
        },
        pub.variable.properties.contentType,
        pub.payload?.let { (it as JsBuffer).buffer },
    )
}

fun toPub(
    p: PersistablePublishMessage,
    userProperty: List<Pair<String, String>>,
) = PublishMessage(
    PublishMessage.FixedHeader(p.dup, p.qos.toQos(), p.retain),
    PublishMessage.VariableHeader(
        Topic.fromOrThrow(p.topicName, Topic.Type.Name),
        p.packetId,
        PublishMessage.VariableHeader.Properties(
            p.payloadFormatIndicator == 1,
            p.messageExpiryInterval?.toLong(),
            p.topicAlias,
            p.responseTopic?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
            p.correlationData?.let { JsBuffer(it, position = it.length, limit = it.length) }
                ?.also { it.resetForRead() },
            userProperty,
            p.subscriptionIdentifier?.split(", ")?.map { it.toLong() }?.toSet() ?: emptySet(),
            p.contentType,
        ),
    ),
    p.payload?.let { JsBuffer(it, position = it.length, limit = it.length) }?.also { it.resetForRead() },
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
    val tls: Boolean,
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
            connectionOps.map {
                when (it) {
                    is MqttConnectionOptions.SocketConnection -> {
                        PersistableSocketConnection(
                            "tcp",
                            it.host,
                            it.port,
                            it.tls,
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
                            it.tls,
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
            p.tls.unsafeCast<Boolean>(),
            (p.connectionTimeoutMs as String).toLong().milliseconds,
            (p.readTimeoutMs as String).toLong().milliseconds,
            (p.writeTimeoutMs as String).toLong().milliseconds,
        )
    } else {
        MqttConnectionOptions.WebSocketConnectionOptions(
            p.host as String,
            p.port as Int,
            p.tls as Boolean,
            (p.connectionTimeoutMs as String).toLong().milliseconds,
            (p.readTimeoutMs as String).toLong().milliseconds,
            (p.writeTimeoutMs as String).toLong().milliseconds,
            (p.websocketEndpoint ?: "") as String,
            (p.websocketProtocols as? String)?.split(", ") ?: emptyList(),
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
            val props = connectionRequest.payload.willProperties
            return PersistableConnectionRequest(
                connectionRequest.variableHeader.protocolName,
                connectionRequest.variableHeader.protocolVersion.toInt(),
                connectionRequest.variableHeader.willRetain,
                connectionRequest.variableHeader.willQos.integerValue,
                connectionRequest.variableHeader.willFlag,
                connectionRequest.variableHeader.cleanStart,
                connectionRequest.variableHeader.keepAliveSeconds,
                connectionRequest.variableHeader.properties.sessionExpiryIntervalSeconds?.toString(),
                connectionRequest.variableHeader.properties.receiveMaximum,
                connectionRequest.variableHeader.properties.maximumPacketSize?.toString(),
                connectionRequest.variableHeader.properties.topicAliasMaximum,
                connectionRequest.variableHeader.properties.requestResponseInformation,
                connectionRequest.variableHeader.properties.requestProblemInformation,
                connectionRequest.variableHeader.properties.authentication?.method,
                connectionRequest.variableHeader.properties.authentication?.data?.let { (it as JsBuffer).buffer },
                connectionRequest.payload.clientId,
                props != null,
                connectionRequest.payload.willTopic?.toString(),
                (connectionRequest.payload.willPayload as? JsBuffer)?.buffer,
                connectionRequest.payload.userName,
                connectionRequest.payload.password,
                props?.willDelayIntervalSeconds?.toInt() ?: 0,
                props?.payloadFormatIndicator ?: false,
                props?.messageExpiryIntervalSeconds?.toString(),
                props?.contentType,
                props?.responseTopic?.toString(),
                props?.correlationData?.let { (it as JsBuffer).buffer },
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
    val authData = (p.authData as Int8Array?)?.let { JsBuffer(it, position = it.length, limit = it.length) }
    val auth =
        if (authMethod != null && authData != null) {
            Authentication(authMethod, authData)
        } else {
            null
        }
    val willProps =
        if (p.hasWillProperties as Boolean) {
            ConnectionRequest.Payload.WillProperties(
                (p.willPropertyWillDelayIntervalSeconds as Int).toLong(),
                p.willPropertyPayloadFormatIndicator as Boolean,
                (p.willPropertyMessageExpiryIntervalSeconds as String?)?.toLong(),
                p.willPropertyContentType as String?,
                (p.willPropertyResponseTopic as String?)?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
                p.willPropertyCorrelationData?.unsafeCast<Int8Array>()
                    ?.let { JsBuffer(it, position = 0, limit = it.length) },
                willUserProperty,
            )
        } else {
            null
        }
    val variableHeaderProps =
        ConnectionRequest.VariableHeader.Properties(
            (p.sessionExpiryIntervalSeconds as String?)?.toULong(),
            p.receiveMaximum as Int?,
            (p.maximumPacketSize as String?)?.toULong(),
            p.topicAliasMaximum as Int?,
            p.requestResponseInformation as Boolean?,
            p.requestProblemInformation as Boolean?,
            userProperty,
            auth,
        )
    return ConnectionRequest(
        ConnectionRequest.VariableHeader(
            p.protocolName as String,
            (p.protocolLevel as Int).toUByte(),
            p.username != null,
            p.password != null,
            p.willRetain as Boolean,
            (p.willQos as Byte).toQos(),
            p.willFlag as Boolean,
            p.cleanSession as Boolean,
            p.keepAliveSeconds as Int,
            variableHeaderProps,
        ),
        ConnectionRequest.Payload(
            p.clientId as String,
            willProps,
            (p.willTopic as? String)?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
            p.willPayload?.unsafeCast<Int8Array>()
                ?.let { JsBuffer(it, position = it.length, limit = it.length) },
            p.username as? String,
            p.password as? String,
        ),
    )
}

fun Byte.toQos(): QualityOfService {
    return when (toInt()) {
        1 -> QualityOfService.AT_LEAST_ONCE
        2 -> QualityOfService.EXACTLY_ONCE
        else -> QualityOfService.AT_MOST_ONCE
    }
}
