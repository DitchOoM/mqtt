package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.connection.MqttConnectionOptions

/**
 * Maps an [MqttConnectionOptions] subtype to the [MqttTransport] that can open it. Implement this to
 * plug in custom transports or to compose the built-in ones differently (e.g. substitute
 * WebTransport for QUIC on the web). Pass a custom resolver to [defaultSingleConnection].
 */
fun interface MqttTransportResolver {
    fun resolve(options: MqttConnectionOptions): MqttTransport
}

/**
 * Default routing over the built-in [MqttConnectionOptions] subtypes.
 *
 * QUIC and WebTransport are currently stubs (see [QuicMqttTransport] / [WebTransportMqttTransport]).
 * When they land, the intended composition is a platform-aware resolver that routes
 * [MqttConnectionOptions.QuicConnectionOptions] to QUIC natively but to WebTransport on JS/wasmJs,
 * where QUIC throws because browsers do not expose raw UDP. That platform split belongs in a custom
 * resolver (or an expect/actual override of this one) rather than here.
 */
object DefaultMqttTransportResolver : MqttTransportResolver {
    override fun resolve(options: MqttConnectionOptions): MqttTransport =
        when (options) {
            is MqttConnectionOptions.SocketConnection -> TcpMqttTransport
            is MqttConnectionOptions.WebSocketConnectionOptions -> WebSocketMqttTransport
            is MqttConnectionOptions.QuicConnectionOptions -> QuicMqttTransport
            is MqttConnectionOptions.WebTransportConnectionOptions -> WebTransportMqttTransport
        }
}
