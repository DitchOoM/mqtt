package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.connection.MqttConnectionOptions
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Guards the [DefaultMqttTransportResolver] routing: each [MqttConnectionOptions] subtype must map to
 * its transport. Pure wiring — no network — so it runs on every platform.
 */
class MqttTransportResolverTest {
    private val resolver = DefaultMqttTransportResolver

    @Test
    fun socketConnectionResolvesToTcp() =
        assertSame(
            TcpMqttTransport,
            resolver.resolve(MqttConnectionOptions.SocketConnection(host = "h", port = 1883)),
        )

    @Test
    fun webSocketResolvesToWebSocketTransport() =
        assertSame(
            WebSocketMqttTransport,
            resolver.resolve(MqttConnectionOptions.WebSocketConnectionOptions(host = "h", port = 80)),
        )

    @Test
    fun quicResolvesToQuicTransport() =
        assertSame(
            QuicMqttTransport,
            resolver.resolve(MqttConnectionOptions.QuicConnectionOptions(host = "h", port = 14567)),
        )

    @Test
    fun webTransportResolvesToWebTransportTransport() =
        assertSame(
            WebTransportMqttTransport,
            resolver.resolve(MqttConnectionOptions.WebTransportConnectionOptions(host = "h", port = 443)),
        )
}
