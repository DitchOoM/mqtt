package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.PublishResult
import com.ditchoom.mqtt.client.QoS1State
import com.ditchoom.mqtt.client.QoS2State
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.ConnectWillProperties
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MqttClientTest {
    internal val inMemory: Boolean = true
    private val isAndroidDevice: Boolean = getPlatform() == Platform.Android
    private val host = if (isAndroidDevice) "10.0.2.2" else "localhost"
    private val testMqttConnectionOptions =
        MqttConnectionOptions.SocketConnection(
            host,
            1883,
            tlsEnabled = false,
            connectionTimeout = 10.seconds,
        )
    private val testWsMqttConnectionOptions =
        MqttConnectionOptions.WebSocketConnectionOptions(
            host,
            8080,
            websocketEndpoint = "/mqtt",
            tlsEnabled = false,
            protocols = listOf("mqttv3.1"),
            connectionTimeout = 10.seconds,
        )
    private val testWsMqtt5ConnectionOptions =
        MqttConnectionOptions.WebSocketConnectionOptions(
            host,
            8080,
            websocketEndpoint = "/mqtt",
            tlsEnabled = false,
            protocols = listOf("mqtt"),
            connectionTimeout = 10.seconds,
        )
    private val connectionRequestMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 1),
            payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt()),
        )
    private val connectionRequestMqtt5 =
        com.ditchoom.mqtt5.controlpacket.ConnectionRequest(
            clientId = "taco123-" + Random.nextUInt(),
            keepAliveSeconds = 1,
            cleanStart = true,
        )
    private val connectionRequestResumeSessionMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = false, keepAliveSeconds = 1),
            payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt()),
        )
    private val connectionRequestResumeSessionMqtt5 =
        com.ditchoom.mqtt5.controlpacket.ConnectionRequest(
            clientId = "taco123-" + Random.nextUInt(),
            keepAliveSeconds = 1,
            cleanStart = false,
        )
    private val topic = TopicName.fromOrThrow("hello123")
    private val willTopic4 = TopicName.fromOrThrow("willTopicMqtt4")
    private val willTopic5 = TopicName.fromOrThrow("willTopicMqtt5")
    private val payloadString = "Taco"

    @Test
    fun clientEcho4() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            clientEchoInternal(this, testMqttConnectionOptions, connectionRequestMqtt4)
        }

    @Test
    fun clientEchoMqtt5() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            clientEchoInternal(this, testMqttConnectionOptions, connectionRequestMqtt5)
        }

    @Test
    fun clientWebsocketEcho4() =
        runTestNoTimeSkipping {
            clientEchoInternal(this, testWsMqttConnectionOptions, connectionRequestMqtt4)
        }

    @Test
    fun clientWebsocketEcho5() =
        runTestNoTimeSkipping {
            clientEchoInternal(this, testWsMqtt5ConnectionOptions, connectionRequestMqtt5)
        }

    @Test
    fun stayConnectedEcho4() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            stayConnectedEchoInternal(this, testMqttConnectionOptions, connectionRequestResumeSessionMqtt4)
        }

    @Test
    fun stayConnectedEcho5() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping
            stayConnectedEchoInternal(this, testMqttConnectionOptions, connectionRequestResumeSessionMqtt5)
        }

    @Test
    fun stayConnectedEchoWebsockets4() =
        runTestNoTimeSkipping {
            stayConnectedEchoInternal(this, testWsMqttConnectionOptions, connectionRequestResumeSessionMqtt4)
        }

    @Test
    fun stayConnectedEchoWebsockets5() =
        runTestNoTimeSkipping {
            stayConnectedEchoInternal(this, testWsMqtt5ConnectionOptions, connectionRequestResumeSessionMqtt5)
        }

    @Test
    fun highAvailabilityBadPortConnectOnceMqtt4() =
        runTestNoTimeSkipping {
            highAvailabilityBadPortConnectOnceInternal(this, connectionRequestMqtt4)
        }

    @Test
    fun highAvailabilityBadPortConnectOnceMqtt5() =
        runTestNoTimeSkipping {
            highAvailabilityBadPortConnectOnceInternal(this, connectionRequestMqtt5)
        }

    private suspend fun highAvailabilityBadPortConnectOnceInternal(
        scope: CoroutineScope,
        connectionRequest: IConnectionRequest,
    ) {
        val isMqtt5 = connectionRequest.controlPacketFactory.protocolVersion == 5
        val wsProtocol = if (isMqtt5) "mqtt" else "mqttv3.1"
        val wsBadPort =
            MqttConnectionOptions.WebSocketConnectionOptions(
                host,
                2,
                websocketEndpoint = "/mqtt",
                tlsEnabled = false,
                protocols = listOf(wsProtocol),
                connectionTimeout = 1.seconds,
            )
        val goodOptions = if (isMqtt5) testWsMqtt5ConnectionOptions else testWsMqttConnectionOptions
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val connections = listOf(wsBadPort, goodOptions)
        val broker = persistence.addBroker(connections, connectionRequest)
        val client = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))
        assertEquals(2L, client.connectionAttempts())
        assertEquals(1L, client.connectionCount())
        client.shutdown()
    }

    @Test
    fun highAvailabilityBadPortStayConnectedMqtt4() =
        runTestNoTimeSkipping {
            highAvailabilityBadPortStayConnectedInternal(this, connectionRequestMqtt4)
        }

    @Test
    fun highAvailabilityBadPortStayConnectedMqtt5() =
        runTestNoTimeSkipping {
            highAvailabilityBadPortStayConnectedInternal(this, connectionRequestMqtt5)
        }

    private suspend fun highAvailabilityBadPortStayConnectedInternal(
        scope: CoroutineScope,
        connectionRequest: IConnectionRequest,
    ) {
        val isMqtt5 = connectionRequest.controlPacketFactory.protocolVersion == 5
        val wsProtocol = if (isMqtt5) "mqtt" else "mqttv3.1"
        val wsBadPort =
            MqttConnectionOptions.WebSocketConnectionOptions(
                host,
                2,
                websocketEndpoint = "/mqtt",
                tlsEnabled = false,
                protocols = listOf(wsProtocol),
                connectionTimeout = 1.seconds,
            )
        val goodOptions = if (isMqtt5) testWsMqtt5ConnectionOptions else testWsMqttConnectionOptions
        val connections = listOf(wsBadPort, goodOptions)
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connections, connectionRequest)
        val client = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))
        client.awaitConnectivity()
        assertEquals(2L, client.connectionAttempts())
        assertEquals(1L, client.connectionCount())
        client.shutdown()
    }

    @Test
    fun pingMqtt4() =
        runTestNoTimeSkipping {
            pingInternal(this, testWsMqttConnectionOptions, connectionRequestMqtt4)
        }

    @Test
    fun pingMqtt5() =
        runTestNoTimeSkipping {
            pingInternal(this, testWsMqtt5ConnectionOptions, connectionRequestMqtt5)
        }

    private suspend fun pingInternal(
        scope: CoroutineScope,
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ) {
        val persistence = InMemoryPersistence()
        val broker = persistence.addBroker(connectionOptions, connectionRequest)
        val expectedPingCount = 2
        val client = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))
        // Wait long enough for keepAlive pings to be exchanged
        withTimeout((connectionRequestMqtt4.variableHeader.keepAliveSeconds * expectedPingCount + 5).seconds) {
            while (client.pingResponseCount() < expectedPingCount) {
                delay(0.25.seconds)
            }
        }
        client.shutdown()
        assertEquals(expectedPingCount.toLong(), client.pingCount())
        assertEquals(expectedPingCount.toLong(), client.pingResponseCount())
    }

    @Test
    fun lastWillTestamentMqtt4() =
        runTestNoTimeSkipping {
            val buffer = BufferFactory.Default.allocate(4)
            buffer.writeString("yolo", Charset.UTF8)
            buffer.resetForRead()
            val lwtConnectionRequest =
                ConnectionRequest(
                    connectionRequestMqtt4.variableHeader.copy(
                        cleanSession = false,
                        willRetain = true,
                        willFlag = true,
                        willQos = QualityOfService.AT_MOST_ONCE,
                    ),
                    connectionRequestMqtt4.payload.copy(
                        clientId = "taco321-${Random.nextUInt()}",
                        willTopic = willTopic4,
                        willPayload = buffer,
                    ),
                ).validateOrThrow() as IConnectionRequest

            lastWillTestamentInternal(this, willTopic4, lwtConnectionRequest, connectionRequestMqtt4)
        }

    @Test
    fun lastWillTestamentMqtt5() =
        runTestNoTimeSkipping {
            val buffer = BufferFactory.Default.allocate(4)
            buffer.writeString("yolo", Charset.UTF8)
            buffer.resetForRead()
            val lwtConnectionRequest =
                com.ditchoom.mqtt5.controlpacket
                    .ConnectionRequest(
                        clientId = "taco321-${Random.nextUInt()}",
                        keepAliveSeconds = 1,
                        cleanStart = false,
                        will =
                            WillConfig.Enabled(
                                topic = willTopic5,
                                payload = buffer,
                                qos = QualityOfService.AT_MOST_ONCE,
                                retain = true,
                            ),
                        willProperties = ConnectWillProperties(),
                    ).validateOrThrow() as IConnectionRequest

            lastWillTestamentInternal(this, willTopic5, lwtConnectionRequest, connectionRequestMqtt5)
        }

    private suspend fun lastWillTestamentInternal(
        scope: CoroutineScope,
        willTopic: TopicName,
        lwtConnectionRequest: IConnectionRequest,
        connectionRequest: IConnectionRequest,
    ) {
        val isMqtt5 = connectionRequest.controlPacketFactory.protocolVersion == 5
        val wsOptions = if (isMqtt5) testWsMqtt5ConnectionOptions else testWsMqttConnectionOptions
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val brokerLwt = persistence.addBroker(wsOptions, lwtConnectionRequest)
        val clientLwt = MqttClient.start(scope, brokerLwt, persistence, createConnectFactory(brokerLwt))
        val broker = persistence.addBroker(wsOptions, connectionRequest)
        val clientOther = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))

        val willTopicFilter = TopicFilter.fromOrThrow(willTopic.toString())
        val receivedLwt =
            scope.async {
                val result = clientOther.observe(willTopicFilter).take(1).first()
                clientOther.unsubscribe(connectionRequest.controlPacketFactory.unsubscribe(willTopicFilter)).unsubAck.await()
                clientOther.sendDisconnect()
                clientOther.shutdown()
                result
            }
        clientOther.subscribe(
            connectionRequest.controlPacketFactory.subscribe(
                willTopicFilter,
                QualityOfService.AT_LEAST_ONCE,
            ),
        )
        clientLwt.shutdown(sendDisconnect = false)
        val message = receivedLwt.await()
        assertEquals(message.topic.toString(), willTopic.toString())
        val payload = checkNotNull(message.rawPayload())
        assertEquals("yolo", payload.readString(payload.remaining(), Charset.UTF8))
    }

    private suspend fun stayConnectedEchoInternal(
        scope: CoroutineScope,
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ) {
        Mutex(true)
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connectionOptions, connectionRequest)
        val client = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))
        client.awaitConnectivity()
        sendAllMessageTypes2(client)
        client.sendDisconnect()
        client.awaitConnectivity()
        client.shutdown()
        assertEquals(2, client.connectionCount())
        assertTrue(persistence.isQueueClear(broker))
    }

    private suspend fun clientEchoInternal(
        scope: CoroutineScope,
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ) {
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connectionOptions, connectionRequest)
        val client = MqttClient.start(scope, broker, persistence, createConnectFactory(broker))
        val flow = client.observe(TopicFilter.fromOrThrow(topic.toString()))
        val collectJob =
            scope.launch {
                flow.filterIsInstance<PublishMessage>().take(3).collect {
                    val payload = it.rawPayload() ?: EMPTY_BUFFER
                    val qosValue = it.qualityOfService.integerValue.toString()
                    assertEquals(payloadString + qosValue, payload.readString(payload.limit()))
                }
            }
        sendAllMessageTypes2(client)
        collectJob.join()
        client.shutdown(drain = true)
    }

    private suspend fun sendAllMessageTypes2(client: MqttClient) = sendAllMessageTypes(client, topic, payloadString)
}

suspend fun sendAllMessageTypes(
    client: MqttClient,
    topic: TopicName,
    payloadString: String,
) {
    val factory = client.packetFactory
    val topicFilter = TopicFilter.fromOrThrow(topic.toString())
    val pubQos0 =
        factory.publish(
            topicName = topic,
            qos = QualityOfService.AT_MOST_ONCE,
            payload = (payloadString + "0").toReadBuffer(Charset.UTF8),
        )
    val pubQos1 =
        factory.publish(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            payload = (payloadString + "1").toReadBuffer(Charset.UTF8),
        )
    val pubQos2 =
        factory.publish(
            topicName = topic,
            qos = QualityOfService.EXACTLY_ONCE,
            payload = (payloadString + "2").toReadBuffer(Charset.UTF8),
        )
    client.subscribe(factory.subscribe(topicFilter, maximumQos = QualityOfService.EXACTLY_ONCE)).subAck.await()
    val pub = client.publish(pubQos2)
    if (pub is PublishResult.QoS2) pub.state.first { it is QoS2State.Complete }
    val pub0 = client.publish(pubQos0) // QoS 0 — no ack to wait for
    val pub1 = client.publish(pubQos1)
    if (pub1 is PublishResult.QoS1) pub1.state.first { it is QoS1State.Acknowledged }
    client.unsubscribe(factory.unsubscribe(topicFilter)).unsubAck.await()
}
