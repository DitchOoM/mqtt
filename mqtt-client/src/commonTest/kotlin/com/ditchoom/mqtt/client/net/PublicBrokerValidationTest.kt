package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.getNetworkCapabilities
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.TcpTransport
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest as ConnectionRequestV5

/**
 * Validates MQTT connectivity against public brokers.
 *
 * broker.hivemq.com (standard TLS certs):
 * - 1883: MQTT unencrypted
 * - 8883: MQTT TLS
 * - 8000: MQTT over WebSocket unencrypted
 * - 8884: MQTT over WebSocket TLS
 *
 * test.mosquitto.org:
 * - 1883: MQTT unencrypted
 * - 8080: MQTT over WebSocket unencrypted
 * - 8081: MQTT over WebSocket TLS
 *
 * All `*Websocket*` tests below are `@Ignore`d: [openConnection] throws for WS transport
 * because this test harness has no WS → `Connection<ControlPacket>` adapter yet. The real
 * factory at mqtt-client commonMain `MqttConnectionFactory.connectSingle` shows the shape
 * (TcpTransport → byteStream → `connectWebSocket(binaryCodec = MqttCodec(factory))` →
 * `mapNotNull` into `Connection<ControlPacket>`). Port that into this class (or extract a
 * shared helper) to re-enable. `mosquittoTcpPlaintext`/`V5` are ignored separately for
 * external-endpoint flake.
 */
class PublicBrokerValidationTest {
    // --- HiveMQ public broker (standard TLS certs) ---

    @Test
    fun hivemqTcpPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 1883, tlsEnabled = false, connectionTimeout = 15.seconds),
            )
        }

    @Test
    fun hivemqTcpTls() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tlsEnabled = true, connectionTimeout = 15.seconds),
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8000,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketTls() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8884,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    // --- test.mosquitto.org ---

    // test.mosquitto.org:1883 is externally flaky — TCP handshake reaches the broker but CONNACK
    // does not come back in time (30s test timeout), same pattern as websocket's
    // `mosquittoWssConnect` ignore. Equivalent TCP coverage via hivemq endpoints.
    @Ignore
    @Test
    fun mosquittoTcpPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("test.mosquitto.org", 1883, tlsEnabled = false, connectionTimeout = 15.seconds),
            )
        }

    @Ignore
    @Test
    fun mosquittoWebsocketPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8080,
                    websocketEndpoint = "/",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    @Ignore
    @Test
    fun mosquittoWebsocketTls() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8081,
                    websocketEndpoint = "/",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    // --- TLS stability tests: sustained communication ---

    /**
     * Tests multiple sequential publishes over TLS to verify the TLS session
     * state is maintained correctly across multiple write/read cycles.
     */
    @Test
    fun hivemqTcpTlsMultiplePublishes() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectMultiplePublishes(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tlsEnabled = true, connectionTimeout = 15.seconds),
                publishCount = 5,
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketTlsMultiplePublishes() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectMultiplePublishes(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8884,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                publishCount = 5,
            )
        }

    /**
     * Tests subscribe + receive over TLS. This exercises the full bidirectional
     * TLS path: subscribe (write), suback (read), publish to self (write),
     * receive published message (read), puback (read).
     */
    @Test
    fun hivemqTcpTlsSubscribeReceive() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectSubscribeReceive(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tlsEnabled = true, connectionTimeout = 15.seconds),
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketTlsSubscribeReceive() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectSubscribeReceive(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8884,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    // --- MQTT v5 tests ---

    @Test
    fun hivemqTcpPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 1883, tlsEnabled = false, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Test
    fun hivemqTcpTlsV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tlsEnabled = true, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8000,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketTlsV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8884,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    @Test
    fun hivemqTcpTlsSubscribeReceiveV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectSubscribeReceive(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tlsEnabled = true, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Ignore
    @Test
    fun hivemqWebsocketTlsSubscribeReceiveV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectSubscribeReceive(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "broker.hivemq.com",
                    8884,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    // Same external-endpoint flake as `mosquittoTcpPlaintext` above.
    @Ignore
    @Test
    fun mosquittoTcpPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("test.mosquitto.org", 1883, tlsEnabled = false, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Ignore
    @Test
    fun mosquittoWebsocketPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8080,
                    websocketEndpoint = "/",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    @Ignore
    @Test
    fun mosquittoWebsocketTlsV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8081,
                    websocketEndpoint = "/",
                    tlsEnabled = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    // --- Helpers ---

    private fun makeConnectionRequest(
        clientId: String,
        mqttV5: Boolean,
    ): IConnectionRequest =
        if (mqttV5) {
            ConnectionRequestV5(clientId = clientId)
        } else {
            ConnectionRequest(payload = ConnectionRequest.Payload(clientId = clientId))
        }

    /**
     * Opens a [MessageConnection] for the given [MqttConnectionOptions].
     * TCP connections use [CodecConnection] + [MqttCodec].
     * WebSocket connections are not yet supported (requires WebSocketByteStream adapter).
     */
    private suspend fun openConnection(
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ): Connection<ControlPacket> {
        val factory = connectionRequest.controlPacketFactory
        return when (connectionOptions) {
            is MqttConnectionOptions.SocketConnection -> {
                val socketOptions =
                    if (connectionOptions.tlsEnabled) {
                        SocketOptions(
                            tls =
                                TlsConfig(
                                    verifyCertificates = connectionOptions.tlsVerifyCerts,
                                    verifyHostname = connectionOptions.tlsVerifyHostname,
                                    allowExpiredCertificates = connectionOptions.tlsAllowExpired,
                                    allowSelfSigned = connectionOptions.tlsAllowSelfSigned,
                                ),
                        )
                    } else {
                        SocketOptions()
                    }
                CodecConnection.connect(
                    connectionOptions.host,
                    connectionOptions.port,
                    MqttCodec(factory),
                    TcpTransport(),
                    ConnectionOptions(
                        socketOptions = socketOptions,
                        connectionTimeout = connectionOptions.connectionTimeout,
                        readTimeout = connectionOptions.readTimeout,
                        writeTimeout = connectionOptions.writeTimeout,
                    ),
                )
            }

            is MqttConnectionOptions.WebSocketConnectionOptions -> {
                // TODO(mqtt-client test harness): mirror the WS branch of MqttConnectionFactory.connectSingle
                //   (commonMain net/MqttConnectionFactory.kt:60). It builds TcpTransport → byteStream →
                //   connectWebSocket(binaryCodec = MqttCodec(factory)) → mapNotNull into
                //   Connection<ControlPacket>. Inlining that here (or extracting a shared test helper)
                //   unblocks every `*Websocket*` test currently @Ignore'd at the top of this class.
                throw UnsupportedOperationException(
                    "WebSocket transport not yet supported in tests. Requires WebSocketByteStream adapter.",
                )
            }
        }
    }

    /**
     * Sends CONNECT, validates CONNACK, and returns the connection.
     */
    private suspend fun connectAndValidate(
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
        clientId: String,
    ): Connection<ControlPacket> {
        val connection = openConnection(connectionOptions, connectionRequest)
        connection.send(connectionRequest as ControlPacket)
        val connack = connection.receive().first()
        assertTrue(connack is IConnectionAcknowledgment, "CONNACK failed for $clientId, got ${connack::class.simpleName}")
        assertTrue(connack.isSuccessful, "CONNACK rejected for $clientId: ${connack.connectionReason}")
        return connection
    }

    private suspend fun connectPublishDisconnect(
        connectionOptions: MqttConnectionOptions,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-test-${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val connection = connectAndValidate(connectionOptions, connectionRequest, clientId)

        val publish =
            connectionRequest.controlPacketFactory
                .publish(
                    topicName = TopicName.fromOrThrow("ditchoom/validation/test"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                ).maybeCopyWithNewPacketIdentifier(1)
        connection.send(publish)
        val ack = connection.receive().first()
        assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK, got ${ack::class.simpleName}")

        connection.send(connectionRequest.controlPacketFactory.disconnect())
        connection.close()
    }

    private suspend fun connectMultiplePublishes(
        connectionOptions: MqttConnectionOptions,
        publishCount: Int,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-multi-${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val connection = connectAndValidate(connectionOptions, connectionRequest, clientId)

        repeat(publishCount) { i ->
            val publish =
                connectionRequest.controlPacketFactory
                    .publish(
                        topicName = TopicName.fromOrThrow("ditchoom/validation/multi/$i"),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(i + 1)
            connection.send(publish)
            val ack = connection.receive().first()
            assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK for message $i, got ${ack::class.simpleName}")
        }

        connection.send(connectionRequest.controlPacketFactory.disconnect())
        connection.close()
    }

    private suspend fun connectSubscribeReceive(
        connectionOptions: MqttConnectionOptions,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-sub-${Random.nextInt()}"
        val uniqueTopic = "ditchoom/validation/sub/${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val connection = connectAndValidate(connectionOptions, connectionRequest, clientId)

        // Subscribe
        val subscribe =
            connectionRequest.controlPacketFactory
                .subscribe(TopicFilter.fromOrThrow(uniqueTopic))
                .copyWithNewPacketIdentifier(1)
        connection.send(subscribe)
        val suback = connection.receive().first()
        assertTrue(suback is ISubscribeAcknowledgement, "Expected SUBACK, got ${suback::class.simpleName}")

        // Publish to the topic we subscribed to
        val publish =
            connectionRequest.controlPacketFactory
                .publish(
                    topicName = TopicName.fromOrThrow(uniqueTopic),
                    qos = QualityOfService.AT_LEAST_ONCE,
                ).maybeCopyWithNewPacketIdentifier(2)
        connection.send(publish)

        // Read PUBACK and incoming PUBLISH (order is not guaranteed)
        val packet1 = connection.receive().first()
        val packet2 = connection.receive().first()
        val packets = listOf(packet1, packet2)
        assertTrue(packets.any { it is IPublishAcknowledgment }, "Expected PUBACK in response, got ${packets.map { it::class.simpleName }}")
        val received = packets.filterIsInstance<PublishMessage>().firstOrNull()
        assertTrue(received != null, "Expected incoming PUBLISH, got ${packets.map { it::class.simpleName }}")
        assertEquals(uniqueTopic, received.topic.toString(), "Received message should be on subscribed topic")

        connection.send(connectionRequest.controlPacketFactory.disconnect())
        connection.close()
    }
}
