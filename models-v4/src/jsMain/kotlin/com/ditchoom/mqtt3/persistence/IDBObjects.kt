@file:OptIn(ExperimentalJsExport::class)

package com.ditchoom.mqtt3.persistence

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.Subscription
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration.Companion.milliseconds

data class PersistableQos2Message(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int,
    @JsName("type")
    val type: Byte,
    @JsName("incoming")
    val incoming: Int
)

data class PersistableUnsubscribe(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int
) {
    constructor(brokerId: Int, unsub: UnsubscribeRequest) :
        this(brokerId, unsub.packetIdentifier)
}

// fun toUnsubscribe(p: PersistableUnsubscribe) =
//    UnsubscribeRequest(p.brokerId, p.packetId)//, p.topic.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet())

data class PersistableSubscribe(
    @JsName("brokerId")
    val brokerId: Int,
    @JsName("packetId")
    val packetId: Int
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
    val qos: Byte
) {
    @JsName("build")
    constructor(brokerId: Int, subscribeId: Int, sub: ISubscription) :
        this(brokerId, sub.topicFilter.toString(), subscribeId, -1, sub.maximumQos.integerValue)
}

fun toSubscription(s: PersistableSubscription) =
    Subscription(Topic.fromOrThrow(s.topicFilter, Topic.Type.Filter), s.qos.toQos())

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
    @JsName("payload")
    val payload: Int8Array?
) {
    constructor(brokerId: Int, incoming: Boolean, pub: PublishMessage) : this(
        brokerId,
        if (incoming) 1 else 0,
        pub.fixed.dup,
        pub.fixed.qos.integerValue,
        pub.fixed.retain,
        pub.variable.topicName.toString(),
        pub.variable.packetIdentifier,
        pub.payload?.let { (it as JsBuffer).buffer }
    )
}

fun toPub(p: PersistablePublishMessage) = PublishMessage(
    PublishMessage.FixedHeader(p.dup, p.qos.toQos(), p.retain),
    PublishMessage.VariableHeader(Topic.fromOrThrow(p.topicName, Topic.Type.Name), p.packetId),
    p.payload?.let { JsBuffer(it, position = it.length, limit = it.length) }?.also { it.resetForRead() }
)

data class PersistableBroker(
    @JsName("id")
    val id: Int,
    @JsName("connectionOptions")
    val connectionOptions: Array<PersistableSocketConnection>,
    @JsName("connectionRequest")
    val connectionRequest: PersistableConnectionRequest
)

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
    val websocketProtocols: String?
) {
    companion object {
        fun from(connectionOps: Collection<MqttConnectionOptions>) = connectionOps.map {
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
                        null
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
                        it.protocols.joinToString()
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
            (p.writeTimeoutMs as String).toLong().milliseconds
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
    @JsName("clientId")
    val clientId: String,
    @JsName("willTopic")
    val willTopic: String?,
    @JsName("willPayload")
    val willPayload: Int8Array?,
    @JsName("username")
    val username: String?,
    @JsName("password")
    val password: String?
) {
    companion object {
        fun from(connectionRequest: ConnectionRequest): PersistableConnectionRequest =
            PersistableConnectionRequest(
                connectionRequest.variableHeader.protocolName,
                connectionRequest.variableHeader.protocolLevel.toInt(),
                connectionRequest.variableHeader.willRetain,
                connectionRequest.variableHeader.willQos.integerValue,
                connectionRequest.variableHeader.willFlag,
                connectionRequest.variableHeader.cleanSession,
                connectionRequest.variableHeader.keepAliveSeconds,
                connectionRequest.payload.clientId,
                connectionRequest.payload.willTopic?.toString(),
                (connectionRequest.payload.willPayload as? JsBuffer)?.buffer,
                connectionRequest.payload.userName,
                connectionRequest.payload.password
            )
    }
}

fun toConnectionRequest(a: Any?): ConnectionRequest {
    val p = a.asDynamic()
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
            p.keepAliveSeconds as Int
        ),
        ConnectionRequest.Payload(
            p.clientId as String,
            (p.willTopic as? String)?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
            (p.willPayload as? Int8Array)?.let { JsBuffer(it, limit = it.length) } as? ReadBuffer,
            p.username as? String,
            p.password as? String
        )
    )
}

fun Byte.toQos(): QualityOfService {
    return when (toInt()) {
        1 -> QualityOfService.AT_LEAST_ONCE
        2 -> QualityOfService.EXACTLY_ONCE
        else -> QualityOfService.AT_MOST_ONCE
    }
}
