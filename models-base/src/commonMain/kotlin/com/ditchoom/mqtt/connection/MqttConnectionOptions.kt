package com.ditchoom.mqtt.connection

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface MqttConnectionOptions {
    val host: String
    val port: Int
    val tlsEnabled: Boolean
    val tlsVerifyCerts: Boolean
    val tlsVerifyHostname: Boolean
    val tlsAllowExpired: Boolean
    val tlsAllowSelfSigned: Boolean
    val readTimeout: Duration
    val writeTimeout: Duration
    val connectionTimeout: Duration

    fun copy(
        host: String = this.host,
        port: Int = this.port,
        tlsEnabled: Boolean = this.tlsEnabled,
        tlsVerifyCerts: Boolean = this.tlsVerifyCerts,
        tlsVerifyHostname: Boolean = this.tlsVerifyHostname,
        tlsAllowExpired: Boolean = this.tlsAllowExpired,
        tlsAllowSelfSigned: Boolean = this.tlsAllowSelfSigned,
        connectionTimeout: Duration = this.connectionTimeout,
        readTimeout: Duration = this.readTimeout,
        writeTimeout: Duration = this.writeTimeout,
        isWebsocket: Boolean = this is WebSocketConnectionOptions,
        websocketEndpoint: String = if (this is WebSocketConnectionOptions) this.websocketEndpoint else "/mqtt",
        protocols: List<String> = if (this is WebSocketConnectionOptions) this.protocols else emptyList(),
    ): MqttConnectionOptions =
        if (isWebsocket) {
            WebSocketConnectionOptions(
                host,
                port,
                tlsEnabled,
                tlsVerifyCerts,
                tlsVerifyHostname,
                tlsAllowExpired,
                tlsAllowSelfSigned,
                connectionTimeout,
                readTimeout,
                writeTimeout,
                websocketEndpoint,
                protocols,
            )
        } else {
            SocketConnection(
                host,
                port,
                tlsEnabled,
                tlsVerifyCerts,
                tlsVerifyHostname,
                tlsAllowExpired,
                tlsAllowSelfSigned,
                connectionTimeout,
                readTimeout,
                writeTimeout,
            )
        }

    data class SocketConnection(
        override val host: String,
        override val port: Int,
        override val tlsEnabled: Boolean = port == 8883,
        override val tlsVerifyCerts: Boolean = true,
        override val tlsVerifyHostname: Boolean = true,
        override val tlsAllowExpired: Boolean = false,
        override val tlsAllowSelfSigned: Boolean = false,
        override val connectionTimeout: Duration = 15.seconds,
        override val readTimeout: Duration = connectionTimeout,
        override val writeTimeout: Duration = connectionTimeout,
    ) : MqttConnectionOptions

    data class WebSocketConnectionOptions(
        override val host: String,
        override val port: Int,
        override val tlsEnabled: Boolean = port == 443,
        override val tlsVerifyCerts: Boolean = true,
        override val tlsVerifyHostname: Boolean = true,
        override val tlsAllowExpired: Boolean = false,
        override val tlsAllowSelfSigned: Boolean = false,
        override val connectionTimeout: Duration = 15.seconds,
        override val readTimeout: Duration = connectionTimeout,
        override val writeTimeout: Duration = connectionTimeout,
        val websocketEndpoint: String = "/",
        val protocols: List<String> = listOf("mqtt"),
    ) : MqttConnectionOptions {
        internal fun buildUrl(): String {
            val prefix =
                if (tlsEnabled) {
                    "wss://"
                } else {
                    "ws://"
                }
            val postfix = "$host:$port$websocketEndpoint"
            return prefix + postfix
        }

        companion object {
        }
    }
}
