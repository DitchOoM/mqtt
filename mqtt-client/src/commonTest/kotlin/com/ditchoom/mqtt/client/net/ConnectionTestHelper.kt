package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.client.TopicCodec
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.websocket.tcp.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Plain TCP when [tls] is absent, TLS with [tls] otherwise. */
fun tcpEndpoint(
    host: String,
    port: Int,
    tls: TlsConfig? = null,
    timeout: Duration = 15.seconds,
): MqttEndpoint = MqttEndpoint(host, port, config = testConfig(tls, timeout), connackTimeout = timeout)

/** MQTT over WebSocket with the `mqtt` subprotocol; `wss` when [tls] is present. */
fun webSocketEndpoint(
    host: String,
    port: Int,
    path: String = "/mqtt",
    protocols: List<String> = listOf("mqtt"),
    tls: TlsConfig? = null,
    timeout: Duration = 15.seconds,
): MqttEndpoint =
    MqttEndpoint(
        host,
        port,
        WebSocketTransport(websocketEndpoint = path, protocols = protocols),
        testConfig(tls, timeout),
        timeout,
    )

private fun testConfig(
    tls: TlsConfig?,
    timeout: Duration,
): TransportConfig = TransportConfig.LOW_LATENCY.copy(tls = tls, connectTimeout = timeout)

/** Starts a client that dials [endpoint]. */
suspend fun startClient(
    scope: CoroutineScope,
    broker: MqttBroker,
    persistence: Persistence,
    endpoint: MqttEndpoint,
): MqttClient = MqttClient.start(scope, broker, persistence, endpoint)

/** Starts a client that dials [endpoints] in order. */
suspend fun startClient(
    scope: CoroutineScope,
    broker: MqttBroker,
    persistence: Persistence,
    endpoints: List<MqttEndpoint>,
): MqttClient = MqttClient.start(scope, broker, persistence, endpoints.first(), endpoints.drop(1))

/**
 * One raw MQTT connection with no client around it. With no subscription registry, every
 * PUBLISH decodes as opaque bytes.
 */
internal suspend fun CoroutineScope.openRawConnection(
    endpoint: MqttEndpoint,
    factory: ControlPacketFactory,
): Connection<ControlPacket> = EndpointConnector.connect(this, endpoint, factory) { TopicCodec.Routed(OpaquePublishPayloadCodec) }
