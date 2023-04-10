package com.ditchoom.mqtt.client.net

import block
import blockWithResult
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.LocalMqttClient
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.allocate
import com.ditchoom.socket.getNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MqttClientTest {
    internal val inMemory: Boolean = true
    private val isAndroidDevice: Boolean = blockWithResult {
        try {
            val c = ClientSocket.allocate()
            c.open(1883, 100.milliseconds, "localhost")
            c.close()
            false
        } catch (t: Throwable) {
            true
        }
    }
    private val host = if (isAndroidDevice) "10.0.2.2" else "localhost"
    private val testMqttConnectionOptions = MqttConnectionOptions.SocketConnection(
        host,
        1883,
        tls = false,
        connectionTimeout = 10.seconds
    )
    private val testWsMqttConnectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
        host,
        80,
        websocketEndpoint = "/mqtt",
        tls = false,
        protocols = listOf("mqttv3.1"),
        connectionTimeout = 10.seconds
    )
    private val connectionRequestMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 1),
            payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt())
        )
    private val connectionRequestMqtt5 =
        com.ditchoom.mqtt5.controlpacket.ConnectionRequest(
            variableHeader = com.ditchoom.mqtt5.controlpacket.ConnectionRequest.VariableHeader(
                cleanStart = true,
                keepAliveSeconds = 1
            ),
            payload = com.ditchoom.mqtt5.controlpacket.ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt())
        )
    private val connectionRequestResumeSessionMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = false, keepAliveSeconds = 1),
            payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt())
        )
    private val connectionRequestResumeSessionMqtt5 =
        com.ditchoom.mqtt5.controlpacket.ConnectionRequest(
            variableHeader = com.ditchoom.mqtt5.controlpacket.ConnectionRequest.VariableHeader(
                cleanStart = false,
                keepAliveSeconds = 1
            ),
            payload = com.ditchoom.mqtt5.controlpacket.ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt())
        )
    private val topic = Topic.fromOrThrow("hello123", Topic.Type.Name)
    private val willTopic4 = Topic.fromOrThrow("willTopicMqtt4", Topic.Type.Name)
    private val willTopic5 = Topic.fromOrThrow("willTopicMqtt5", Topic.Type.Name)
    private val payloadString = "Taco"

    @Test
    fun clientEcho4() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        clientEchoInternal(this, testMqttConnectionOptions, connectionRequestMqtt4)
    }

    @Test
    fun clientEchoMqtt5() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        clientEchoInternal(this, testMqttConnectionOptions, connectionRequestMqtt5)
    }

    @Test
    fun clientWebsocketEcho4() = block {
        clientEchoInternal(this, testWsMqttConnectionOptions, connectionRequestMqtt4)
    }

    @Test
    fun clientWebsocketEcho5() = block {
        clientEchoInternal(this, testWsMqttConnectionOptions, connectionRequestMqtt5)
    }

    @Test
    fun stayConnectedEcho4() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        stayConnectedEchoInternal(this, testMqttConnectionOptions, connectionRequestResumeSessionMqtt4)
    }

    @Test
    fun stayConnectedEcho5() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        stayConnectedEchoInternal(this, testMqttConnectionOptions, connectionRequestResumeSessionMqtt5)
    }

    @Test
    fun stayConnectedEchoWebsockets4() = block {
        stayConnectedEchoInternal(this, testWsMqttConnectionOptions, connectionRequestResumeSessionMqtt4)
    }

    @Test
    fun stayConnectedEchoWebsockets5() = block {
        stayConnectedEchoInternal(this, testWsMqttConnectionOptions, connectionRequestResumeSessionMqtt5)
    }

    @Test
    fun highAvailabilityBadPortConnectOnceMqtt4() = block {
        highAvailabilityBadPortConnectOnceInternal(this, connectionRequestMqtt4)
    }

    @Test
    fun highAvailabilityBadPortConnectOnceMqtt5() = block {
        highAvailabilityBadPortConnectOnceInternal(this, connectionRequestMqtt5)
    }

    private suspend fun highAvailabilityBadPortConnectOnceInternal(
        scope: CoroutineScope,
        connectionRequest: IConnectionRequest
    ) {
        val wsBadPort = MqttConnectionOptions.WebSocketConnectionOptions(
            host,
            2,
            websocketEndpoint = "/mqtt",
            tls = false,
            protocols = listOf("mqttv3.1"),
            connectionTimeout = 1.seconds
        )
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val connections = listOf(wsBadPort, testWsMqttConnectionOptions)
        val broker = persistence.addBroker(connections, connectionRequest)
        val client = LocalMqttClient.connectOnce(scope, broker, persistence)
        assertEquals(2L, client.connectionAttempts())
        assertEquals(1L, client.connectionCount())
        client.shutdown()
    }

    @Test
    fun highAvailabilityBadPortStayConnectedMqtt4() = block {
        highAvailabilityBadPortStayConnectedInternal(this, connectionRequestMqtt4)
    }

    @Test
    fun highAvailabilityBadPortStayConnectedMqtt5() = block {
        highAvailabilityBadPortStayConnectedInternal(this, connectionRequestMqtt5)
    }

    private suspend fun highAvailabilityBadPortStayConnectedInternal(
        scope: CoroutineScope,
        connectionRequest: IConnectionRequest
    ) {
        val wsBadPort = MqttConnectionOptions.WebSocketConnectionOptions(
            host,
            2,
            websocketEndpoint = "/mqtt",
            tls = false,
            protocols = listOf("mqttv3.1"),
            connectionTimeout = 1.seconds

        )
        val connections = listOf(wsBadPort, testWsMqttConnectionOptions)
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connections, connectionRequest)
        val client = LocalMqttClient.stayConnected(scope, broker, persistence)
        client.awaitConnectivity()
        assertEquals(2L, client.connectionAttempts())
        assertEquals(1L, client.connectionCount())
        client.shutdown()
    }

    @Test
    fun pingMqtt4() = block {
        pingInternal(this, connectionRequestMqtt4)
    }

    @Test
    fun pingMqtt5() = block {
        pingInternal(this, connectionRequestMqtt5)
    }

    private suspend fun pingInternal(scope: CoroutineScope, connectionRequest: IConnectionRequest) {
        val persistence = InMemoryPersistence()
        val broker = persistence.addBroker(testWsMqttConnectionOptions, connectionRequest)
        var client: LocalMqttClient? = null
        val expectedPingCount = 2
        withTimeout((connectionRequestMqtt4.variableHeader.keepAliveSeconds * expectedPingCount + 5).seconds) {
            val pongs = callbackFlow {
                var count = 0
                val incomingMessageCb: (UByte, Int, ReadBuffer) -> Unit = { byte1, remaining, buffer ->
                    val p = connectionRequest.controlPacketFactory.from(buffer, byte1, remaining)
                    if (p is IPingResponse) {
                        trySend(p)
                        count++
                    }
                    if (count == 2) {
                        channel.close()
                    }
                }
                client = LocalMqttClient.connectOnce(scope, broker, persistence, incomingMessage = incomingMessageCb)
                awaitClose()
            }
            pongs.take(expectedPingCount).toList()
        }
        client!!.shutdown()
        assertEquals(expectedPingCount.toLong(), client!!.pingCount())
        assertEquals(expectedPingCount.toLong(), client!!.pingResponseCount())
    }

    @Test
    fun lastWillTestamentMqtt4() = block {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeString("yolo", Charset.UTF8)
        buffer.resetForRead()
        val lwtConnectionRequest =
            connectionRequestMqtt4.copy(
                connectionRequestMqtt4.variableHeader.copy(
                    cleanSession = false,
                    willRetain = true,
                    willFlag = true,
                    willQos = QualityOfService.AT_MOST_ONCE
                ),
                connectionRequestMqtt4.payload.copy(
                    clientId = "taco321-${Random.nextUInt()}",
                    willTopic = willTopic4,
                    willPayload = buffer
                )
            ).validateOrThrow() as IConnectionRequest

        lastWillTestamentInternal(this, willTopic4, lwtConnectionRequest, connectionRequestMqtt4)
    }

    @Test
    fun lastWillTestamentMqtt5() = block {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeString("yolo", Charset.UTF8)
        buffer.resetForRead()
        val lwtConnectionRequest =
            connectionRequestMqtt5.copy(
                connectionRequestMqtt5.variableHeader.copy(
                    cleanStart = false,
                    willRetain = true,
                    willFlag = true,
                    willQos = QualityOfService.AT_MOST_ONCE
                ),
                connectionRequestMqtt5.payload.copy(
                    clientId = "taco321-${Random.nextUInt()}",
                    willTopic = willTopic5,
                    willPayload = buffer,
                    willProperties = com.ditchoom.mqtt5.controlpacket.ConnectionRequest.Payload.WillProperties()
                )
            ).validateOrThrow() as IConnectionRequest

        lastWillTestamentInternal(this, willTopic5, lwtConnectionRequest, connectionRequestMqtt5)
    }

    private suspend fun lastWillTestamentInternal(
        scope: CoroutineScope,
        willTopic: Topic,
        lwtConnectionRequest: IConnectionRequest,
        connectionRequest: IConnectionRequest
    ) {
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val brokerLwt = persistence.addBroker(testWsMqttConnectionOptions, lwtConnectionRequest)
        val clientLwt = LocalMqttClient.connectOnce(scope, brokerLwt, persistence)
        val broker = persistence.addBroker(testWsMqttConnectionOptions, connectionRequest)
        val clientOther = LocalMqttClient.connectOnce(scope, broker, persistence)

        val receivedLwt = scope.async {
            val result = clientOther.observe(willTopic).take(1).first()
            clientOther.unsubscribe(connectionRequest.controlPacketFactory.unsubscribe(willTopic)).unsubAck.await()
            clientOther.sendDisconnect()
            clientOther.shutdown()
            result
        }
        clientOther.subscribe(
            connectionRequest.controlPacketFactory.subscribe(
                willTopic,
                QualityOfService.AT_LEAST_ONCE
            )
        )
        clientLwt.shutdown(sendDisconnect = false)
        val message = receivedLwt.await()
        assertEquals(message.topic.toString(), willTopic.toString())
        val payload = checkNotNull(message.payload)
        assertEquals("yolo", payload.readString(payload.remaining(), Charset.UTF8))
    }

    private suspend fun stayConnectedEchoInternal(
        scope: CoroutineScope,
        connectionOptions: MqttConnectionOptions,
        connectionRequest: IConnectionRequest
    ) {
        Mutex(true)
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connectionOptions, connectionRequest)
        val client = LocalMqttClient.stayConnected(scope, broker, persistence)
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
        connectionRequest: IConnectionRequest
    ) {
        val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory)
        val broker = persistence.addBroker(connectionOptions, connectionRequest)
        val client = LocalMqttClient.connectOnce(scope, broker, persistence)
        val flow = client.observe(topic)
        scope.launch {
            flow.filterIsInstance<IPublishMessage>().take(3).collect {
                val payload = it.payload ?: EMPTY_BUFFER
                val qosValue = it.qualityOfService.integerValue.toString()
                assertEquals(payloadString + qosValue, payload.readString(payload.limit()))
            }
        }
        sendAllMessageTypes2(client)
        client.shutdown()
        assertTrue(persistence.isQueueClear(broker, false))
    }

    private suspend fun sendAllMessageTypes2(client: MqttClient) =
        sendAllMessageTypes(client, topic, payloadString)
}

suspend fun sendAllMessageTypes(client: MqttClient, topic: Topic, payloadString: String) {
    val factory = client.packetFactory
    val pubQos0 = factory.publish(
        topicName = topic,
        qos = QualityOfService.AT_MOST_ONCE,
        payload = (payloadString + "0").toReadBuffer(Charset.UTF8)
    )
    val pubQos1 = factory.publish(
        topicName = topic,
        qos = QualityOfService.AT_LEAST_ONCE,
        payload = (payloadString + "1").toReadBuffer(Charset.UTF8)
    )
    val pubQos2 = factory.publish(
        topicName = topic,
        qos = QualityOfService.EXACTLY_ONCE,
        payload = (payloadString + "2").toReadBuffer(Charset.UTF8)
    )
    client.subscribe(factory.subscribe(topic, maximumQos = QualityOfService.EXACTLY_ONCE)).subAck.await()
    val pub = client.publish(pubQos2)
    if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY && client.packetFactory.protocolVersion == 5) {
        // TODO: incoming qos2 messages are still in the queue when using clientEchoInternal for some reason with web
        delay(100)
    }
    pub.awaitAll()
    client.publish(pubQos0).awaitAll()
    client.publish(pubQos1).awaitAll()
    client.unsubscribe(factory.unsubscribe(topic)).unsubAck.await()
}
