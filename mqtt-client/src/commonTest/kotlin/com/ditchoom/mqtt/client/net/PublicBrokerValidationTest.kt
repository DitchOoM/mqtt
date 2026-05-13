package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
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
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
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
 * [openConnection] delegates to the production [defaultSingleConnection] factory, so TCP
 * and WebSocket paths go through exactly the same code as runtime clients. The only tests
 * still `@Ignore`d are the `test.mosquitto.org` variants (`mosquittoTcpPlaintext(V5)`,
 * `mosquittoWebsocketPlaintext(V5)`, `mosquittoWebsocketTls(V5)`) — that endpoint flakes
 * the same way websocket's `mosquittoWssConnect` does: handshake accepts, data path
 * hangs or drops. HiveMQ public-broker endpoints exercise the equivalent TCP / WS / TLS
 * matrix on a stable endpoint.
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
            ConnectionRequest(clientId = clientId)
        }

    private fun makePublish(
        topic: String,
        mqttV5: Boolean,
        packetId: Int,
        qos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
    ): com.ditchoom.mqtt.controlpacket.PublishMessage =
        if (mqttV5) {
            com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish
                .ofRaw(
                    topic = TopicName.fromOrThrow(topic),
                    qos = qos,
                ).maybeCopyWithNewPacketIdentifier(packetId)
        } else {
            com.ditchoom.mqtt3.controlpacket.PublishMessageV4
                .ofRaw(
                    topic = TopicName.fromOrThrow(topic),
                    qos = qos,
                ).maybeCopyWithNewPacketIdentifier(packetId)
        }

    /**
     * Opens a [Connection] for the given [MqttConnectionOptions]. Delegates to the production
     * [defaultSingleConnection] factory so tests exercise the same TCP / WebSocket code paths
     * that [MqttClient] uses — no parallel test-only transport adapter to maintain.
     */
    private suspend fun openConnection(
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ): Connection<ControlPacket> = defaultSingleConnection(connectionOptions, connectionRequest.controlPacketFactory)

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

        val publish = makePublish("ditchoom/validation/test", mqttV5, packetId = 1)
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
            val publish = makePublish("ditchoom/validation/multi/$i", mqttV5, packetId = i + 1)
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
        val publish = makePublish(uniqueTopic, mqttV5, packetId = 2)
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
