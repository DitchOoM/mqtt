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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end coverage of the TCP branch of [defaultConnectionFactory] against a
 * hermetic Mosquitto broker started via Testcontainers. Requires Docker.
 */
class DefaultConnectionFactoryTcpIntegrationTest {
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
                MqttConnectionOptions.SocketConnection(
                    host = TestMosquittoBroker.tcpHost(),
                    port = TestMosquittoBroker.tcpPort(),
                    tlsEnabled = false,
                    connectionTimeout = 15.seconds,
                )
            val connectionRequest =
                ConnectionRequest(
                    payload = ConnectionRequest.Payload(clientId = "ditchoom-tcp-${Random.nextInt()}"),
                )
            val connect = defaultConnectionFactory(listOf(options), connectionRequest.controlPacketFactory)
            val connection = connect()

            connection.send(connectionRequest)
            val connack = connection.receive().first()
            assertTrue(connack is IConnectionAcknowledgment, "Expected CONNACK, got ${connack::class.simpleName}")
            assertTrue(connack.isSuccessful, "CONNACK rejected: ${connack.connectionReason}")

            val publish =
                connectionRequest.controlPacketFactory
                    .publish(
                        topicName = TopicName.fromOrThrow("ditchoom/it/tcp"),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(1)
            connection.send(publish)
            val ack = connection.receive().first()
            assertTrue(ack is IPublishAcknowledgment, "Expected PUBACK, got ${ack::class.simpleName}")

            connection.send(connectionRequest.controlPacketFactory.disconnect())
            connection.close()
        }
}
