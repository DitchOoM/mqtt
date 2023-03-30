package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.PlatformBuffer
import kotlin.js.JsName

sealed interface IPCMqttMessage {
    val type: Int
    val brokerIdentifier: Int
    val version: Int

    @JsName("Log")
    data class Log(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
        @JsName("message")
        val message: String,
    ) : IPCMqttMessage {
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 0
        }
    }

    @JsName("BrokerAdded")
    data class BrokerAdded(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
    ) : IPCMqttMessage {

        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 1
        }
    }

    @JsName("RemoveAllBrokersAndStop")
    object RemoveAllBrokersAndStop : IPCMqttMessage {
        const val TYPE = 2
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = -1
        override val version: Int = -1
    }

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
    ) : IPCMqttMessage {
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 3
        }
    }

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
    ) : IPCMqttMessage {
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 4
        }
    }

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
    ) : IPCMqttMessage {
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 5
        }
    }

    @JsName("Shutdown")
    data class Shutdown(
        @JsName("brokerId")
        val brokerId: Int,
        @JsName("protocolVersion")
        val protocolVersion: Int,
    ) : IPCMqttMessage {
        @JsName("serviceWorkerMessageType")
        val serviceWorkerMessageType: Int = TYPE
        override val type: Int = serviceWorkerMessageType
        override val brokerIdentifier: Int = brokerId
        override val version: Int = protocolVersion
        companion object {
            const val TYPE = 6
        }
    }
}
