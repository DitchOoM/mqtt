package com.ditchoom.mqtt5.persistence

import block
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.wrap
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishMessage
import com.ditchoom.mqtt5.controlpacket.PublishReceived
import com.ditchoom.mqtt5.controlpacket.PublishRelease
import com.ditchoom.mqtt5.controlpacket.SubscribeAcknowledgement
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeAcknowledgment
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PersistenceTests {
    private val buffer = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4))

    private suspend fun setupPersistence(): Pair<Persistence, MqttBroker> {
        val p = newDefaultPersistence(name = "test" + Random.nextUInt(), inMemory = true)
        val b = p.allBrokers()
        if (b.isNotEmpty()) {
            return Pair(p, b.first())
        }
        return Pair(
            p,
            p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt5)
        )
    }

    @Test
    fun pubQos1() = block {
        val (persistence, broker) = setupPersistence()
        val pub = PublishMessage(
            topicName = "test",
            qos = QualityOfService.AT_LEAST_ONCE,
            payload = buffer,
            messageExpiryInterval = 5L,
            topicAlias = 1,
            userProperty = listOf(Pair("Rahul", "Behera")),
            subscriptionIdentifier = setOf(5L, 2L)
        )
        val packetId = persistence.writePubGetPacketId(broker, pub)
        assertEquals(
            pub.maybeCopyWithNewPacketIdentifier(packetId),
            persistence.getPubWithPacketId(broker, packetId),
            "get packet"
        )
        val expectedPub = pub.copy(fixed = pub.fixed.copy(dup = true)).maybeCopyWithNewPacketIdentifier(packetId)
        val queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size)
        val queuedPacket = queuedPackets.first()
        assertEquals(expectedPub, queuedPacket, "reconnected message")
        persistence.ackPub(broker, PublishAcknowledgment(packetId))
        assertEquals(0, persistence.messagesToSendOnReconnect(broker).size, "reconnect size")
    }

    @Test
    fun pubQos2() = block {
        val (persistence, broker) = setupPersistence()
        val pub = PublishMessage(
            topicName = "test",
            qos = QualityOfService.EXACTLY_ONCE,
            payload = buffer,
            userProperty = listOf(Pair("Rahul", "Behera"))
        )

        val packetId = persistence.writePubGetPacketId(broker, pub)
        val expectedPub = pub.copy(fixed = pub.fixed.copy(dup = true)).maybeCopyWithNewPacketIdentifier(packetId)
        assertEquals(
            pub.maybeCopyWithNewPacketIdentifier(packetId),
            persistence.getPubWithPacketId(broker, packetId),
            "get packet"
        )
        var queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "queued pub")
        var queuedPacket = queuedPackets.first()
        assertEquals(expectedPub, queuedPacket)

        val pubRel = PublishRelease(packetId, userProperty = listOf(Pair("Yolo", "PubRel")))
        persistence.ackPubReceivedQueuePubRelease(broker, PublishReceived(packetId), pubRel)
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "queued pub rel ${queuedPackets.joinToString()}")
        queuedPacket = queuedPackets.first()
        assertEquals(pubRel, queuedPacket)

        persistence.ackPubComplete(broker, PublishComplete(packetId))
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size)
    }

    @Test
    fun incomingQos2() = block {
        val (persistence, broker) = setupPersistence()
        val packetId = 3
        val pub = PublishMessage(
            topicName = "test",
            qos = QualityOfService.EXACTLY_ONCE,
            payload = buffer,
            packetIdentifier = packetId,
            userProperty = listOf(Pair("Rahul", "Behera"))
        )
        val pubRecv = pub.expectedResponse() as PublishReceived

        persistence.incomingPublish(broker, pub, pubRecv)
        var queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "incoming publish")
        var queuedPacket = queuedPackets.first()
        assertEquals(pubRecv, queuedPacket)

        val pubRel = PublishRelease(packetId, userProperty = listOf(Pair("Incoming", "PubRel")))
        val pubComp = PublishComplete(packetId, userProperty = listOf(Pair("Incoming2", "PubComp")))
        persistence.ackPubRelease(broker, pubRel, pubComp)
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "ack publish release")
        queuedPacket = queuedPackets.first()
        assertEquals(pubComp, queuedPacket)

        persistence.onPubCompWritten(broker, pubComp)
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size, "pub comp written")
    }

    @Test
    fun subscription() = block {
        val (persistence, broker) = setupPersistence()
        val topicMap = HashMap<Topic, QualityOfService>()
        val topic0 = Topic.fromOrThrow("topic0", Topic.Type.Filter)
        val topic1 = Topic.fromOrThrow("topic1", Topic.Type.Filter)
        val topic2 = Topic.fromOrThrow("topic2", Topic.Type.Filter)
        topicMap[topic0] = QualityOfService.AT_MOST_ONCE
        topicMap[topic1] = QualityOfService.AT_LEAST_ONCE
        topicMap[topic2] = QualityOfService.EXACTLY_ONCE
        val subscriptions = setOf(
            Subscription(
                topic0,
                QualityOfService.AT_MOST_ONCE,
                noLocal = false,
                retainAsPublished = true,
                retainHandling = ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
            ),
            Subscription(
                topic1,
                QualityOfService.AT_LEAST_ONCE,
                noLocal = true,
                retainAsPublished = false,
                retainHandling = ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
            ),
            Subscription(
                topic2,
                QualityOfService.EXACTLY_ONCE,
                noLocal = true,
                retainAsPublished = false,
                retainHandling = ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
            )
        )
        val sub = SubscribeRequest(
            SubscribeRequest.VariableHeader(
                NO_PACKET_ID,
                SubscribeRequest.VariableHeader.Properties(
                    "testReason",
                    userProperty = listOf(Pair("Rahul", "Behera"))
                )
            ),
            subscriptions
        )

        val subWithPacketId = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        var packetId = subWithPacketId.packetIdentifier
        assertEquals(subWithPacketId, persistence.getSubWithPacketId(broker, packetId), "get sub")
        var queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "sub: ${queuedPackets.joinToString()}")
        var queuedPacket = queuedPackets.first()
        assertEquals(subWithPacketId, queuedPacket)

        var subs = persistence.activeSubscriptions(broker)
        assertEquals(3, subs.size, "subs: ${subs.values.joinToString()}")
        val sortedActiveSubscriptions = subs.values.toList().sortedBy { it.topicFilter.toString() }
        assertEquals(subs[topic0], sortedActiveSubscriptions[0], "sorted sub 0")
        assertEquals(subs[topic1], sortedActiveSubscriptions[1], "sorted sub 1")
        assertEquals(subs[topic2], sortedActiveSubscriptions[2], "sorted sub 2")

        persistence.ackSub(
            broker,
            SubscribeAcknowledgement(
                packetIdentifier = packetId,
                payload = listOf(ReasonCode.GRANTED_QOS_0, ReasonCode.GRANTED_QOS_1, ReasonCode.GRANTED_QOS_2)
            )
        )
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size, "ackSub: ${queuedPackets.joinToString()}")

        val unsub = UnsubscribeRequest(
            topics = setOf(topic0, topic1, topic2),
            userProperty = listOf(Pair("Rahul", "Behera"))
        )
        packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        assertEquals(
            unsub.copyWithNewPacketIdentifier(packetId),
            persistence.getUnsubWithPacketId(broker, packetId),
            "get unsub"
        )
        val newUnsub = unsub.copyWithNewPacketIdentifier(packetId) as UnsubscribeRequest
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "unsub: ${queuedPackets.joinToString()}")
        queuedPacket = queuedPackets.first()
        assertEquals(packetId, queuedPacket.packetIdentifier, "packetId")
        assertEquals(newUnsub, queuedPacket, "object matches")
        persistence.ackUnsub(
            broker,
            UnsubscribeAcknowledgment(
                packetId,
                reasonCodes = listOf(ReasonCode.SUCCESS, ReasonCode.SUCCESS, ReasonCode.SUCCESS)
            )
        )
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size, "ackUnsub: ${queuedPackets.joinToString()}")
        subs = persistence.activeSubscriptions(broker)
        assertEquals(0, subs.size, "active subs: ${queuedPackets.joinToString()}")
    }

    @Test
    fun broker() = block {
        val p = newDefaultPersistence(inMemory = true)
        assertEquals(0, p.allBrokers().size, "initial broker size")
        val broker = p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt5)
        val allBrokers = p.allBrokers()
        assertEquals(1, allBrokers.size, "single broker size")
        assertEquals(broker.connectionOps, allBrokers.first().connectionOps.toSet(), "broker match connection ops")
        assertEquals(broker.connectionRequest, allBrokers.first().connectionRequest, "broker match connection request")
        assertEquals(broker.identifier, allBrokers.first().identifier, "broker match id")
        p.removeBroker(broker.identifier)
        assertEquals(0, p.allBrokers().size, "empty broker")
    }

    companion object {
        private val testMqttConnectionOptions = MqttConnectionOptions.SocketConnection(
            "localhost",
            1883,
            tls = false,
            connectionTimeout = 10.seconds
        )
        private val testWsMqttConnectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
            "localhost",
            80,
            tls = false,
            protocols = listOf("mqttv3.1"),
            websocketEndpoint = "/mqtt",
            connectionTimeout = 10.seconds

        )
        private val connectionRequestMqtt5 =
            ConnectionRequest(
                variableHeader = ConnectionRequest.VariableHeader(
                    cleanStart = true,
                    keepAliveSeconds = 1,
                    willFlag = true,
                    properties = ConnectionRequest.VariableHeader.Properties(
                        sessionExpiryIntervalSeconds = 1u,
                        receiveMaximum = 500,
                        10_000_000uL,
                        topicAliasMaximum = 40,
                        requestProblemInformation = true,
                        userProperty = listOf(Pair("Rahul", "Behera"), Pair("yolo", "swag"))
                    )
                ),
                payload = ConnectionRequest.Payload(
                    clientId = "taco123-" + Random.nextUInt(),
                    willProperties = ConnectionRequest.Payload.WillProperties(
                        willDelayIntervalSeconds = 1,
                        correlationData = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4)).also { it.position(0) },
                        userProperty = listOf(Pair("will", "test"), Pair("test", "will"))
                    ),
                    willTopic = Topic.fromOrThrow("testWill", Topic.Type.Name)
                )
            )
    }
}
