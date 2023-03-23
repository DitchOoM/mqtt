package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.PlatformBuffer
import kotlin.js.JsName

sealed interface IPCMqttMessage {

    @JsName("Log")
    data class Log(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("message")
        val message: String,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 0,
    ) : IPCMqttMessage

    @JsName("BrokerAdded")
    data class BrokerAdded(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 1,
    ) : IPCMqttMessage

    @JsName("RemoveAllBrokersAndStop")
    data class RemoveAllBrokersAndStop(
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 2,
    ) : IPCMqttMessage

    @JsName("SendControlPacket")
    data class SendControlPacket(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("controlPacketType")
        val controlPacketType: Byte,
        @JsName("packetId")
        val packetId: Int,
        @JsName("qos0PubPayload")
        val qos0PubPayload: PlatformBuffer? = null,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 3,
    ) : IPCMqttMessage

    @JsName("ControlPacketSent")
    data class ControlPacketSent(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("controlPacketType")
        val controlPacketType: Byte,
        @JsName("packetId")
        val packetId: Int,
        @JsName("packetBuffer")
        val packetBuffer: PlatformBuffer,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 4,
    ) : IPCMqttMessage

    @JsName("ControlPacketReceived")
    data class ControlPacketReceived(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("controlPacketType")
        val controlPacketType: Byte,
        @JsName("packetId")
        val packetId: Int,
        @JsName("packetBuffer")
        val packetBuffer: PlatformBuffer,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 5,
    ) : IPCMqttMessage

    @JsName("Shutdown")
    data class Shutdown(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 6,
    ) : IPCMqttMessage
}
