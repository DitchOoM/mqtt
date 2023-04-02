package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.MessageEvent

internal const val MESSAGE_TYPE_KEY = "mqtt-message-type"
private const val MESSAGE_TYPE_CONTROL_PACKET = "mqtt-message-type-control-packet"
internal const val MESSAGE_TYPE_SERVICE_START_ALL = "mqtt-message-service-start-all"
internal const val MESSAGE_TYPE_SERVICE_START_ALL_RESPONSE = "mqtt-message-service-start-all-response"
internal const val MESSAGE_TYPE_SERVICE_START = "mqtt-message-service-start"
internal const val MESSAGE_TYPE_SERVICE_START_RESPONSE = "mqtt-message-service-start-response"
internal const val MESSAGE_TYPE_SERVICE_STOP_ALL = "mqtt-message-service-stop-all"
internal const val MESSAGE_TYPE_SERVICE_STOP_ALL_RESPONSE = "mqtt-message-service-stop-all-response"
internal const val MESSAGE_TYPE_SERVICE_STOP = "mqtt-message-service-stop"
internal const val MESSAGE_TYPE_SERVICE_STOP_RESPONSE = "mqtt-message-service-stop-response"
internal const val MESSAGE_TYPE_REGISTER_CLIENT = "mqtt-message-register-client"
internal const val MESSAGE_TYPE_REGISTER_CLIENT_SUCCESS = "mqtt-message-register-client-success"
internal const val MESSAGE_TYPE_REGISTER_CLIENT_NOT_FOUND = "mqtt-message-register-client-not-found"
internal const val MESSAGE_TYPE_CLIENT_PUBLISH = "mqtt-message-client-publish"
internal const val MESSAGE_TYPE_CLIENT_PUBLISH_COMPLETION = "mqtt-message-client-publish-completion"
internal const val MESSAGE_TYPE_CLIENT_SUBSCRIBE = "mqtt-message-client-subscribe"
internal const val MESSAGE_TYPE_CLIENT_SUBSCRIBE_COMPLETION = "mqtt-message-client-subscribe-completion"
internal const val MESSAGE_TYPE_CLIENT_UNSUBSCRIBE = "mqtt-message-client-unsubscribe"
internal const val MESSAGE_TYPE_CLIENT_UNSUBSCRIBE_COMPLETION = "mqtt-message-client-unsubscribe-completion"
internal const val MESSAGE_TYPE_CLIENT_SHUTDOWN = "mqtt-message-client-shutdown"
internal const val MESSAGE_TYPE_CLIENT_SHUTDOWN_COMPLETION = "mqtt-message-client-shutdown-completion"
internal const val MESSAGE_TYPE_CLIENT_CONNACK_REQUEST = "mqtt-message-client-connack-request"
internal const val MESSAGE_TYPE_CLIENT_CONNACK_RESPONSE = "mqtt-message-client-connack-response"
internal const val MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_REQUEST = "mqtt-message-client-await-connectivity-request"
internal const val MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_RESPONSE = "mqtt-message-client-await-connectivity-response"
internal const val MESSAGE_TYPE_CLIENT_PING_COUNT_REQUEST = "mqtt-message-client-ping-count-request"
internal const val MESSAGE_TYPE_CLIENT_PING_COUNT_RESPONSE = "mqtt-message-client-ping-count-response"
internal const val MESSAGE_TYPE_CLIENT_PING_RESPONSE_COUNT_REQUEST = "mqtt-message-client-ping-response-count-request"
internal const val MESSAGE_TYPE_CLIENT_PING_RESPONSE_COUNT_RESPONSE = "mqtt-message-client-ping-response-count-response"
internal const val MESSAGE_TYPE_CLIENT_SEND_DISCONNECT = "mqtt-message-client-send-disconnect"
internal const val MESSAGE_TYPE_CLIENT_SEND_DISCONNECT_ACK = "mqtt-message-client-send-disconnect-ack"
internal const val MESSAGE_TYPE_CLIENT_CONNECTION_COUNT_REQUEST = "mqtt-message-client-connection-count-request"
internal const val MESSAGE_TYPE_CLIENT_CONNECTION_COUNT_RESPONSE = "mqtt-message-client-connection-count-response"
internal const val MESSAGE_TYPE_CLIENT_CONNECTION_ATTEMPTS_REQUEST = "mqtt-message-client-connection-attempts-request"
internal const val MESSAGE_TYPE_CLIENT_CONNECTION_ATTEMPTS_RESPONSE = "mqtt-message-client-connection-attempts-response"

private const val MESSAGE_BROKER_ID_KEY = "mqtt-broker-id-key"
private const val MESSAGE_PACKET_ID_KEY = "mqtt-packet-id-key"
private const val MESSAGE_PROTOCOL_VERSION_KEY = "mqtt-protocol-version-key"
private const val MESSAGE_INCOMING_KEY = "mqtt-incoming-key"
private const val MESSAGE_POSITION_KEY = "mqtt-position-key"
private const val MESSAGE_LIMIT_KEY = "mqtt-limit-key"
private const val MESSAGE_IS_SHARED_BUFFER_KEY = "mqtt-is-shared-buffer-key"
private const val MESSAGE_BUFFER_KEY = "mqtt-buffer-key"
internal const val MESSAGE_BOOLEAN_KEY = "mqtt-boolean-key"
internal const val MESSAGE_INT_KEY = "mqtt-int-key"
private const val MESSAGE_LONG_KEY = "mqtt-long-key"

fun buildSimpleMessage(messageType: String, optionalBoolean: Boolean? = null, optionalInt: Int? = null): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = messageType
    if (optionalBoolean != null) {
        obj[MESSAGE_BOOLEAN_KEY] = optionalBoolean
    }
    if (optionalInt != null) {
        obj[MESSAGE_INT_KEY] = optionalInt
    }
    return obj
}

fun buildSimpleMessage(messageType: String, int: Int): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = messageType
    obj[MESSAGE_INT_KEY] = int
    return obj
}

fun messageMatches(messageType: String, int: Int, obj: dynamic): Boolean {
    return obj[MESSAGE_TYPE_KEY] == messageType && obj[MESSAGE_INT_KEY] == int
}

fun buildLongMessage(messageType: String, long: Long): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = messageType
    obj[MESSAGE_LONG_KEY] = long.toString()
    return obj
}

fun buildBrokerIdProtocolVersionMessage(
    messageType: String,
    brokerId: Int,
    protocolVersion: Byte,
    optionalInt: Int? = null
): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = messageType
    obj[MESSAGE_BROKER_ID_KEY] = brokerId
    obj[MESSAGE_PROTOCOL_VERSION_KEY] = protocolVersion
    if (optionalInt != null) {
        obj[MESSAGE_INT_KEY] = optionalInt
    }
    return obj
}

fun readBrokerIdProtocolVersionMessage(data: dynamic): Pair<Int, Byte>? {
    if (data[MESSAGE_BROKER_ID_KEY] == null || data[MESSAGE_BROKER_ID_KEY] == undefined ||
        data[MESSAGE_PROTOCOL_VERSION_KEY] == null || data[MESSAGE_PROTOCOL_VERSION_KEY] == undefined
    ) {
        return null
    }
    return Pair(data[MESSAGE_BROKER_ID_KEY].unsafeCast<Int>(), data[MESSAGE_PROTOCOL_VERSION_KEY].unsafeCast<Byte>())
}

fun buildPacketIdMessage(
    messageType: String,
    packetId: Int,
    buffer: JsBuffer? = null,
    optionalInt: Int? = null
): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = messageType
    obj[MESSAGE_PACKET_ID_KEY] = packetId
    writeBufferInMessage(obj, buffer)
    if (optionalInt != null) {
        obj[MESSAGE_INT_KEY] = optionalInt
    }
    return obj
}

fun readLongDataFromMessage(data: dynamic): Long = data[MESSAGE_LONG_KEY].toString().toLong()

fun readPacketIdMessage(factory: ControlPacketFactory, data: dynamic): Pair<Int, ControlPacket?>? {
    if (data[MESSAGE_TYPE_KEY] == null || data[MESSAGE_TYPE_KEY] == undefined) {
        return null
    }
    val packetId = if (data[MESSAGE_PACKET_ID_KEY] == null || data[MESSAGE_PACKET_ID_KEY] == undefined) {
        return null
    } else {
        data[MESSAGE_PACKET_ID_KEY] as Int
    }
    val packet = if (data[MESSAGE_IS_SHARED_BUFFER_KEY] == null || data[MESSAGE_IS_SHARED_BUFFER_KEY] == undefined ||
        data[MESSAGE_BUFFER_KEY] == null || data[MESSAGE_BUFFER_KEY] == undefined ||
        data[MESSAGE_POSITION_KEY] == null || data[MESSAGE_POSITION_KEY] == undefined ||
        data[MESSAGE_LIMIT_KEY] == null || data[MESSAGE_LIMIT_KEY] == undefined
    ) {
        null
    } else {
        val position = data[MESSAGE_POSITION_KEY] as Int
        val limit = data[MESSAGE_LIMIT_KEY] as Int
        val buffer = if (data[MESSAGE_IS_SHARED_BUFFER_KEY] == true) {
            val sharedArrayBuffer = data[MESSAGE_IS_SHARED_BUFFER_KEY].unsafeCast<SharedArrayBuffer>()
            JsBuffer(
                Uint8Array(sharedArrayBuffer as ArrayBuffer),
                false,
                position,
                limit,
                sharedArrayBuffer.byteLength,
                sharedArrayBuffer
            )
        } else {
            val arrayBuffer = data[MESSAGE_IS_SHARED_BUFFER_KEY].unsafeCast<ArrayBuffer>()
            JsBuffer(Uint8Array(arrayBuffer), false, position, limit, arrayBuffer.byteLength)
        }
        buffer.resetForRead()
        factory.from(buffer)
    }
    return Pair(packetId, packet)
}

fun buildControlPacketMessage(incoming: Boolean, jsBuffer: JsBuffer): dynamic {
    val obj = js("({})")
    obj[MESSAGE_TYPE_KEY] = MESSAGE_TYPE_CONTROL_PACKET
    obj[MESSAGE_INCOMING_KEY] = incoming
    writeBufferInMessage(obj, jsBuffer)
    return obj
}

fun writeBufferInMessage(obj: dynamic, jsBuffer: JsBuffer?) {
    if (jsBuffer != null) {
        obj[MESSAGE_IS_SHARED_BUFFER_KEY] = jsBuffer.sharedArrayBuffer != null
        obj[MESSAGE_POSITION_KEY] = jsBuffer.position()
        obj[MESSAGE_LIMIT_KEY] = jsBuffer.limit()
        obj[MESSAGE_BUFFER_KEY] = if (jsBuffer.sharedArrayBuffer != null) {
            jsBuffer.sharedArrayBuffer
        } else {
            jsBuffer.buffer.buffer
        }
    }
}

fun readControlPacketFromMessageEvent(factory: ControlPacketFactory, m: MessageEvent): Pair<Boolean, ControlPacket>? {
    val data = m.data ?: return null
    val obj = data.asDynamic()
    if (obj.MESSAGE_TYPE_KEY != MESSAGE_TYPE_CONTROL_PACKET) {
        return null
    }
    val incoming = if (obj.MESSAGE_INCOMING_KEY != undefined && obj.MESSAGE_INCOMING_KEY != null) {
        obj.MESSAGE_INCOMING_KEY == true
    } else {
        return null
    }
    val position = if (obj.MESSAGE_POSITION_KEY != undefined && obj.MESSAGE_POSITION_KEY != null) {
        obj.MESSAGE_POSITION_KEY as? Int ?: return null
    } else {
        return null
    }
    val limit = if (obj.MESSAGE_LIMIT_KEY != undefined && obj.MESSAGE_LIMIT_KEY != null) {
        obj.MESSAGE_LIMIT_KEY as? Int ?: return null
    } else {
        return null
    }
    val isSharedBuffer =
        if (obj.MESSAGE_IS_SHARED_BUFFER_KEY != undefined && obj.MESSAGE_IS_SHARED_BUFFER_KEY != null) {
            obj.MESSAGE_IS_SHARED_BUFFER_KEY == true
        } else {
            return null
        }
    val buffer = if (obj.MESSAGE_BUFFER_KEY != undefined && obj.MESSAGE_BUFFER_KEY != null) {
        if (isSharedBuffer) {
            val arrayBuffer = obj.MESSAGE_BUFFER_KEY.unsafeCast<SharedArrayBuffer>()
            JsBuffer(
                Uint8Array(arrayBuffer as ArrayBuffer),
                false,
                position,
                limit,
                arrayBuffer.byteLength,
                arrayBuffer
            )
        } else {
            val arrayBuffer = obj.MESSAGE_BUFFER_KEY.unsafeCast<ArrayBuffer>()
            JsBuffer(Uint8Array(arrayBuffer), false, position, limit, arrayBuffer.byteLength, null)
        }
    } else {
        return null
    }
    buffer.resetForRead()
    return Pair(incoming, factory.from(buffer))
}
