package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.networkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MqttSocketSessionTest {
    private val isAndroidDevice: Boolean = getPlatform() == Platform.Android
    private val host = if (isAndroidDevice) "10.0.2.2" else "localhost"

    //    @Test
    fun connectTls() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val connectionOptions =
                tcpEndpoint("test.mosquitto.org", 8886, tls = TlsConfig.DEFAULT, timeout = 10.seconds)
            connectTest(connectionOptions)
        }

    @Test
    fun connectLocalhostMqtt4() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val connectionOptions = tcpEndpoint(host, 1883, timeout = 10.seconds)
            connectTest(connectionOptions, 4)
        }

    @Test
    fun connectLocalhostMqtt5() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val connectionOptions = tcpEndpoint(host, 1883, timeout = 10.seconds)
            connectTest(connectionOptions, 5)
        }

    @Test
    fun connectWebsockets() =
        runTestNoTimeSkipping {
            // Mosquitto container (mqtt-client/build.gradle.kts) binds 8080 for plain WS; same
            // endpoint `stayConnectedEchoWebsockets*` uses.
            val connectionOptions =
                webSocketEndpoint(host, 8080, path = "/mqtt", protocols = listOf("mqttv3.1"), timeout = 10.seconds)
            connectTest(connectionOptions, version = 4)
        }

    //    @Test
    fun connectTestMosquitto() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val connectionOptions =
                tcpEndpoint("test.mosquitto.org", 1883, timeout = 10.seconds)
            connectTest(connectionOptions)
        }

    private suspend fun CoroutineScope.connectTest(
        connectionOptions: MqttEndpoint,
        version: Int = 4,
    ) {
        var testCompleted = false
        try {
            val connectionRequest =
                if (version == 4) {
                    ConnectionRequest(clientId = "taco123-" + Random.nextInt())
                } else {
                    com.ditchoom.mqtt5.controlpacket
                        .ConnectionRequest(clientId = "taco123-" + Random.nextInt())
                }
            val factory = connectionRequest.controlPacketFactory
            val connection = openRawConnection(connectionOptions, factory)
            // Send CONNECT and read CONNACK
            connection.send(connectionRequest)
            val connack = connection.receive().first()
            assertTrue(connack is IConnectionAcknowledgment, "Expected CONNACK, got ${connack::class.simpleName}")
            assertTrue(connack.isSuccessful)

            val publish =
                if (version == 4) {
                    com.ditchoom.mqtt3.controlpacket
                        .PublishMessageV4
                        .ofRaw(
                            topic = TopicName.fromOrThrow("testtt"),
                            qos = QualityOfService.AT_LEAST_ONCE,
                        ).maybeCopyWithNewPacketIdentifier(1)
                } else {
                    com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish
                        .ofRaw(
                            topic = TopicName.fromOrThrow("testtt"),
                            qos = QualityOfService.AT_LEAST_ONCE,
                        ).maybeCopyWithNewPacketIdentifier(1)
                }
            connection.send(publish)
            val controlPacketAck = connection.receive().first()
            assertTrue { controlPacketAck is IPublishAcknowledgment }
            connection.send(factory.disconnect())
            connection.close()
            testCompleted = true
        } catch (e: Exception) {
            throw e
        } finally {
            check(testCompleted) { "Failed to complete test with error" }
        }
    }
}
