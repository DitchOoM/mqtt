package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.client.MqttSocketSession
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest as ConnectionRequestV5
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
 */
class PublicBrokerValidationTest {
    // --- HiveMQ public broker (standard TLS certs) ---

    @Test
    fun hivemqTcpPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 1883, tls = false, connectionTimeout = 15.seconds),
            )
        }

    @Test
    fun hivemqTcpTls() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tls = true, connectionTimeout = 15.seconds),
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
                    tls = false,
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
                    tls = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    // --- test.mosquitto.org ---

    @Test
    fun mosquittoTcpPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("test.mosquitto.org", 1883, tls = false, connectionTimeout = 15.seconds),
            )
        }

    @Test
    fun mosquittoWebsocketPlaintext() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8080,
                    websocketEndpoint = "/",
                    tls = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
            )
        }

    @Test
    fun mosquittoWebsocketTls() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8081,
                    websocketEndpoint = "/",
                    tls = true,
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
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tls = true, connectionTimeout = 15.seconds),
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
                    tls = true,
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
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tls = true, connectionTimeout = 15.seconds),
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
                    tls = true,
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
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 1883, tls = false, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Test
    fun hivemqTcpTlsV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tls = true, connectionTimeout = 15.seconds),
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
                    tls = false,
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
                    tls = true,
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
                MqttConnectionOptions.SocketConnection("broker.hivemq.com", 8883, tls = true, connectionTimeout = 15.seconds),
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
                    tls = true,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    @Test
    fun mosquittoTcpPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            connectPublishDisconnect(
                MqttConnectionOptions.SocketConnection("test.mosquitto.org", 1883, tls = false, connectionTimeout = 15.seconds),
                mqttV5 = true,
            )
        }

    @Test
    fun mosquittoWebsocketPlaintextV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8080,
                    websocketEndpoint = "/",
                    tls = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                ),
                mqttV5 = true,
            )
        }

    @Test
    fun mosquittoWebsocketTlsV5() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            connectPublishDisconnect(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8081,
                    websocketEndpoint = "/",
                    tls = true,
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

    private suspend fun connectPublishDisconnect(
        connectionOptions: MqttConnectionOptions,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-test-${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val session = MqttSocketSession.open(-1, connectionRequest, connectionOptions)
        assertTrue(session.connectionAcknowledgement.isSuccessful, "CONNACK failed for $clientId")

        val publish =
            connectionRequest.controlPacketFactory
                .publish(
                    topicName = Topic.fromOrThrow("ditchoom/validation/test", Topic.Type.Name),
                    qos = QualityOfService.AT_LEAST_ONCE,
                ).maybeCopyWithNewPacketIdentifier(1)
        session.write(publish)
        val ack = session.read()
        assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK, got ${ack::class.simpleName}")

        session.write(connectionRequest.controlPacketFactory.disconnect())
        session.close()
    }

    private suspend fun connectMultiplePublishes(
        connectionOptions: MqttConnectionOptions,
        publishCount: Int,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-multi-${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val session = MqttSocketSession.open(-1, connectionRequest, connectionOptions)
        assertTrue(session.connectionAcknowledgement.isSuccessful, "CONNACK failed for $clientId")

        repeat(publishCount) { i ->
            val publish =
                connectionRequest.controlPacketFactory
                    .publish(
                        topicName = Topic.fromOrThrow("ditchoom/validation/multi/$i", Topic.Type.Name),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(i + 1)
            session.write(publish)
            val ack = session.read()
            assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK for message $i, got ${ack::class.simpleName}")
        }

        session.write(connectionRequest.controlPacketFactory.disconnect())
        session.close()
    }

    private suspend fun connectSubscribeReceive(
        connectionOptions: MqttConnectionOptions,
        mqttV5: Boolean = false,
    ) {
        val clientId = "ditchoom-sub-${Random.nextInt()}"
        val uniqueTopic = "ditchoom/validation/sub/${Random.nextInt()}"
        val connectionRequest = makeConnectionRequest(clientId, mqttV5)
        val session = MqttSocketSession.open(-1, connectionRequest, connectionOptions)
        assertTrue(session.connectionAcknowledgement.isSuccessful, "CONNACK failed for $clientId")

        // Subscribe
        val subscribe =
            connectionRequest.controlPacketFactory
                .subscribe(Topic.fromOrThrow(uniqueTopic, Topic.Type.Filter))
                .copyWithNewPacketIdentifier(1)
        session.write(subscribe)
        val suback = session.read()
        assertTrue(suback is ISubscribeAcknowledgement, "Expected SUBACK, got ${suback::class.simpleName}")

        // Publish to the topic we subscribed to
        val publish =
            connectionRequest.controlPacketFactory
                .publish(
                    topicName = Topic.fromOrThrow(uniqueTopic, Topic.Type.Name),
                    qos = QualityOfService.AT_LEAST_ONCE,
                ).maybeCopyWithNewPacketIdentifier(2)
        session.write(publish)

        // Read PUBACK and incoming PUBLISH (order is not guaranteed)
        val packet1 = session.read()
        val packet2 = session.read()
        val packets = listOf(packet1, packet2)
        assertTrue(packets.any { it is IPublishAcknowledgment }, "Expected PUBACK in response, got ${packets.map { it::class.simpleName }}")
        val received = packets.filterIsInstance<IPublishMessage>().firstOrNull()
        assertTrue(received != null, "Expected incoming PUBLISH, got ${packets.map { it::class.simpleName }}")
        assertEquals(uniqueTopic, received.topic.toString(), "Received message should be on subscribed topic")

        session.write(connectionRequest.controlPacketFactory.disconnect())
        session.close()
    }
}
