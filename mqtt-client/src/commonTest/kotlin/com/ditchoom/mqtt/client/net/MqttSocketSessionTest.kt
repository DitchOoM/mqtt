package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.client.MqttSocketSession
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
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
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            val connectionOptions =
                MqttConnectionOptions.SocketConnection(
                    "test.mosquitto.org",
                    8886,
                    tlsEnabled = true,
                    connectionTimeout = 10.seconds,
                )
            connectTest(connectionOptions)
        }

    @Test
    fun connectLocalhostMqtt4() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            val connectionOptions = MqttConnectionOptions.SocketConnection(host, 1883, tlsEnabled = false, connectionTimeout = 10.seconds)
            connectTest(connectionOptions, 4)
        }

    @Test
    fun connectLocalhostMqtt5() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            val connectionOptions = MqttConnectionOptions.SocketConnection(host, 1883, tlsEnabled = false, connectionTimeout = 10.seconds)
            connectTest(connectionOptions, 5)
        }

    @Test
    fun connectWebsockets() =
        runTestNoTimeSkipping {
            val connectionOptions =
                MqttConnectionOptions.WebSocketConnectionOptions(
                    host,
                    80,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                )
            connectTest(connectionOptions)
        }

    //    @Test
    fun connectTestMosquitto() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            val connectionOptions =
                MqttConnectionOptions.SocketConnection(
                    "test.mosquitto.org",
                    1883,
                    tlsEnabled = false,
                    connectionTimeout = 10.seconds,
                )
            connectTest(connectionOptions)
        }

    //    @Test
    fun connectWebsocketsTestMosquitto() =
        runTestNoTimeSkipping {
            val connectionOptions =
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "test.mosquitto.org",
                    8081,
                    websocketEndpoint = "/mqtt",
                    tlsEnabled = true,
                    protocols = listOf("mqttv3.1"),
                )
            connectTest(connectionOptions)
        }

    private suspend fun connectTest(
        connectionOptions: MqttConnectionOptions,
        version: Int = 4,
    ) {
        var testCompleted = false
        try {
            val connectionRequest =
                if (version == 4) {
                    ConnectionRequest(payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextInt()))
                } else {
                    com.ditchoom.mqtt5.controlpacket
                        .ConnectionRequest(clientId = "taco123-" + Random.nextInt())
                }
            val socketSession = MqttSocketSession.open(-1, connectionRequest, connectionOptions)
            assertTrue(socketSession.connectionAcknowledgement.isSuccessful)
            val publish =
                connectionRequest.controlPacketFactory
                    .publish(
                        topicName = TopicName.fromOrThrow("testtt"),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(1)
            socketSession.write(publish)
            val controlPacketAck = socketSession.read()
            assertTrue { controlPacketAck is IPublishAcknowledgment }
            socketSession.write(connectionRequest.controlPacketFactory.disconnect())
            socketSession.close()
            testCompleted = true
        } catch (e: Exception) {
            throw e
        } finally {
            check(testCompleted) { "Failed to complete test with error" }
        }
    }
}
