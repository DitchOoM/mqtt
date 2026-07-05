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

    /**
     * MQTT over QUIC (**experimental, non-standard** — mirrors EMQX's mapping). The entire MQTT byte
     * stream is tunneled over a single bidirectional QUIC stream, so the existing packet framing is
     * reused unchanged. QUIC is always encrypted, so [tlsEnabled] defaults to `true`.
     *
     * Native only: QUIC needs raw UDP, which browsers do not expose — use
     * [WebTransportConnectionOptions] on the web. Constructed directly (the [copy] helper only
     * toggles between [SocketConnection] and [WebSocketConnectionOptions]).
     */
    data class QuicConnectionOptions(
        override val host: String,
        override val port: Int,
        val alpnProtocols: List<String> = listOf("mqtt"),
        override val tlsEnabled: Boolean = true,
        override val tlsVerifyCerts: Boolean = true,
        override val tlsVerifyHostname: Boolean = true,
        override val tlsAllowExpired: Boolean = false,
        override val tlsAllowSelfSigned: Boolean = false,
        override val connectionTimeout: Duration = 15.seconds,
        override val readTimeout: Duration = connectionTimeout,
        override val writeTimeout: Duration = connectionTimeout,
    ) : MqttConnectionOptions

    /**
     * MQTT over WebTransport (**experimental**, no standard mapping exists). The browser-and-native
     * substitute for [QuicConnectionOptions] where raw UDP is unavailable: WebTransport rides
     * HTTP/3 (QUIC) and is available on every target, including the browser. The MQTT byte stream is
     * tunneled over a single bidirectional WebTransport stream. Always secure (HTTP/3), so
     * [tlsEnabled] defaults to `true`. Constructed directly (see [copy] note on [QuicConnectionOptions]).
     */
    data class WebTransportConnectionOptions(
        override val host: String,
        override val port: Int,
        override val tlsEnabled: Boolean = true,
        override val tlsVerifyCerts: Boolean = true,
        override val tlsVerifyHostname: Boolean = true,
        override val tlsAllowExpired: Boolean = false,
        override val tlsAllowSelfSigned: Boolean = false,
        override val connectionTimeout: Duration = 15.seconds,
        override val readTimeout: Duration = connectionTimeout,
        override val writeTimeout: Duration = connectionTimeout,
        val endpoint: String = "/mqtt",
    ) : MqttConnectionOptions {
        internal fun buildUrl(): String = "https://$host:$port$endpoint"
    }
}
