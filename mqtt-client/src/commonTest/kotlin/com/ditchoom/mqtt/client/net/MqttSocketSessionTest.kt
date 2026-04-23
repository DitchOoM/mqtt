package com.ditchoom.mqtt.client.net

import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.QualityOfService
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

    // Marker for WS transport in this helper — requires the WebSocketByteStream test adapter that
    // also blocks PublicBrokerValidationTest.openConnection's WS branch. Ignored until that adapter
    // lands; keep the @Test so it shows up in coverage as "pending".
    @Ignore
    @Test
    fun connectWebsockets() =
        runTestNoTimeSkipping {
            throw UnsupportedOperationException("WebSocket transport not yet supported in tests")
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
            throw UnsupportedOperationException("WebSocket transport not yet supported in tests")
        }

    private suspend fun connectTest(
        connectionOptions: MqttConnectionOptions,
        version: Int = 4,
    ) {
        require(connectionOptions is MqttConnectionOptions.SocketConnection) {
            "Only TCP socket connections are supported in this test"
        }
        var testCompleted = false
        try {
            val connectionRequest =
                if (version == 4) {
                    ConnectionRequest(payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextInt()))
                } else {
                    com.ditchoom.mqtt5.controlpacket
                        .ConnectionRequest(clientId = "taco123-" + Random.nextInt())
                }
            val factory = connectionRequest.controlPacketFactory
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
            val connection =
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
            // Send CONNECT and read CONNACK
            connection.send(connectionRequest)
            val connack = connection.receive().first()
            assertTrue(connack is IConnectionAcknowledgment, "Expected CONNACK, got ${connack::class.simpleName}")
            assertTrue(connack.isSuccessful)

            val publish =
                factory
                    .publish(
                        topicName = TopicName.fromOrThrow("testtt"),
                        qos = QualityOfService.AT_LEAST_ONCE,
                    ).maybeCopyWithNewPacketIdentifier(1)
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
