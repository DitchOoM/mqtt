package com.ditchoom.mqtt.client.net

import block
import com.ditchoom.mqtt.client.MqttSocketSession
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MqttSocketSessionTest {

    //    @Test
    fun connectTls() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val connectionOptions = MqttConnectionOptions.SocketConnection(
            "test.mosquitto.org",
            8886,
            tls = true,
            connectionTimeout = 10.seconds
        )
        connectTest(connectionOptions)
    }

    @Test
    fun connectLocalhostMqtt4() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val connectionOptions = MqttConnectionOptions.SocketConnection("localhost", 1883, false, 10.seconds)
        connectTest(connectionOptions, 4)
    }

    @Test
    fun connectLocalhostMqtt5() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val connectionOptions = MqttConnectionOptions.SocketConnection("localhost", 1883, false, 10.seconds)
        connectTest(connectionOptions, 5)
    }

    @Test
    fun connectWebsockets() = block {
        val connectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
            "localhost", 80, websocketEndpoint = "/mqtt", tls = false, protocols = listOf("mqtt")
        )
        connectTest(connectionOptions)
    }

    //    @Test
    fun connectTestMosquitto() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val connectionOptions = MqttConnectionOptions.SocketConnection(
            "test.mosquitto.org",
            1883,
            tls = false,
            connectionTimeout = 10.seconds
        )
        connectTest(connectionOptions)
    }

    //    @Test
    fun connectWebsocketsTestMosquitto() = block {
        val connectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
            "test.mosquitto.org", 8081, websocketEndpoint = "/mqtt", tls = true, protocols = listOf("mqttv3.1")
        )
        connectTest(connectionOptions)
    }

    private suspend fun connectTest(connectionOptions: MqttConnectionOptions, version: Int = 4) {
        var testCompleted = false
        try {
            val connectionRequest =
                if (version == 4) {
                    ConnectionRequest(payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextInt()))
                } else {
                    com.ditchoom.mqtt5.controlpacket.ConnectionRequest(clientId = "taco123-" + Random.nextInt())
                }
            val socketSession = MqttSocketSession.open(-1, connectionRequest, connectionOptions)
            assertTrue(socketSession.connectionAcknowledgement.isSuccessful)
            val publish = connectionRequest.controlPacketFactory.publish(
                topicName = Topic.fromOrThrow("testtt", Topic.Type.Name), qos = QualityOfService.AT_LEAST_ONCE,
            ).maybeCopyWithNewPacketIdentifier(1)
            socketSession.write(publish)
            val controlPacketAck = socketSession.read()
            assertTrue { controlPacketAck is IPublishAcknowledgment }
            socketSession.write(connectionRequest.controlPacketFactory.disconnect())
            socketSession.close()
            testCompleted = true
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            check(testCompleted) { "Failed to complete test with error" }
        }
    }
}
