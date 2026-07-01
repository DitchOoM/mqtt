package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.flow.first
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Ignore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of the WebSocket branch of [defaultConnectionFactory] against a
 * hermetic Mosquitto broker started via Testcontainers. Requires Docker.
 *
 * Ignored while the WebSocket transport is gated (WebSocketMqttTransport throws pending the
 * websocket library's migration to buffer 6). Remove @Ignore when restoring the WS transport —
 * see TODO.md and WebSocketMqttTransport.
 */
@Ignore("WebSocket transport gated pending websocket buffer-6 migration — see WebSocketMqttTransport / TODO.md")
class DefaultConnectionFactoryWsIntegrationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun startBroker() {
            assumeTrue("Docker not available — skipping", TestMosquittoBroker.isDockerAvailable())
            if (!TestMosquittoBroker.container.isRunning) {
                TestMosquittoBroker.container.start()
            }
        }

        @AfterClass
        @JvmStatic
        fun stopBroker() {
            if (TestMosquittoBroker.container.isRunning) {
                TestMosquittoBroker.container.stop()
            }
        }
    }

    @Test
    fun connectPublishDisconnect() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val options =
                MqttConnectionOptions.WebSocketConnectionOptions(
                    host = TestMosquittoBroker.wsHost(),
                    port = TestMosquittoBroker.wsPort(),
                    websocketEndpoint = "/",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 15.seconds,
                )
            val connectionRequest = ConnectionRequest(clientId = "ditchoom-ws-${Random.nextInt()}")
            val connection = defaultSingleConnection(options, connectionRequest.controlPacketFactory)

            connection.send(connectionRequest)
            val connack = connection.receive().first()
            assertTrue(connack is IConnectionAcknowledgment, "Expected CONNACK, got ${connack::class.simpleName}")
            assertTrue(connack.isSuccessful, "CONNACK rejected: ${connack.connectionReason}")

            val publish =
                com.ditchoom.mqtt3.controlpacket.PublishMessageV4
                    .ofRaw(
                        topic = TopicName.fromOrThrow("ditchoom/it/ws"),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(1)
            connection.send(publish)
            val ack = connection.receive().first()
            assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK, got ${ack::class.simpleName}")

            connection.send(connectionRequest.controlPacketFactory.disconnect())
            connection.close()
        }
}
