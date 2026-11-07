package com.ditchoom.mqtt.client

import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.socket.transport.Transport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One way to reach an MQTT broker: any socket [Transport] dialled at [host]:[port] with [config].
 *
 * The transport decides what carries the MQTT byte stream, and the client frames that stream the
 * same way whatever it is:
 * - TCP: [TcpTransport] (the default).
 * - TLS: any transport whose [config] carries `tls`, e.g. `TransportConfig.tlsDefault()`.
 * - WebSocket: `com.ditchoom.websocket.tcp.WebSocketTransport(websocketEndpoint = "/mqtt",
 *   protocols = listOf("mqtt"))` from `com.ditchoom:websocket-tcp`. TLS gives `wss`.
 * - QUIC: `com.ditchoom.socket.quic.QuicTransport` from `com.ditchoom:socket-quic-default`, with
 *   ALPN `"mqtt"`. MQTT over QUIC is non-standard, but EMQX implements it.
 * - Anything else that is a [Transport]: `FallbackTransport` chains, `MemoryTransport` in tests,
 *   or your own.
 *
 * Reads on an MQTT connection never time out: a session is idle between messages and liveness is
 * the keep-alive's job, so the client always connects with `ReadPolicy.UntilClosed` whatever
 * [config] says. [config]'s `connectTimeout` bounds the transport handshake, and [connackTimeout]
 * bounds the wait for the broker's CONNACK after it.
 */
data class MqttEndpoint(
    val host: String,
    val port: Int,
    val transport: Transport = TcpTransport(),
    val config: TransportConfig = TransportConfig.LOW_LATENCY,
    val connackTimeout: Duration = 15.seconds,
)
