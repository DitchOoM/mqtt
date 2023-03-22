package com.ditchoom.mqtt.serviceworker

import org.khronos.webgl.Uint8Array

sealed interface MqttServiceWorkerMessage {

    @JsName("Log")
    data class Log(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("message")
        val message: String,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 0,
    ) : MqttServiceWorkerMessage

    @JsName("BrokerAdded")
    data class BrokerAdded(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 1,
    ) : MqttServiceWorkerMessage

    @JsName("RemoveAllBrokersAndStop")
    data class RemoveAllBrokersAndStop(
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 2,
    ) : MqttServiceWorkerMessage

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
        val qos0PubPayload: Uint8Array? = null,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 3,
    ) : MqttServiceWorkerMessage

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
        val packetBuffer: Uint8Array,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 4,
    ) : MqttServiceWorkerMessage

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
        val packetBuffer: Uint8Array,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 5,
    ) : MqttServiceWorkerMessage

    @JsName("Shutdown")
    data class Shutdown(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = 6,
    ) : MqttServiceWorkerMessage

    companion object {
        fun from(obj: dynamic): MqttServiceWorkerMessage? {
            return when (obj.serviceWorkerMessageType.unsafeCast<Int>()) {
                0 -> Log(obj.brokerId, obj.message)
                1 -> BrokerAdded(obj.brokerId, obj.protocolVersion)
                2 -> RemoveAllBrokersAndStop()
                3 -> SendControlPacket(
                    obj.brokerId,
                    obj.protocolVersion,
                    obj.controlPacketType,
                    obj.packetId,
                    obj.qos0PubPayload
                )

                4 -> ControlPacketSent(
                    obj.brokerId,
                    obj.protocolVersion,
                    obj.controlPacketType,
                    obj.packetId,
                    obj.packetBuffer
                )

                5 -> ControlPacketReceived(
                    obj.brokerId,
                    obj.protocolVersion,
                    obj.controlPacketType,
                    obj.packetId,
                    obj.packetBuffer
                )

                6 -> Shutdown(obj.brokerId, obj.protocolVersion)
                else -> null
            }
        }
    }
}