package com.ditchoom.mqtt3.persistence

import block
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.wrap
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubscribeAcknowledgement
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.UnsubscribeAcknowledgment
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
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
            p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt4)
        )
    }

    @Test
    fun pubQos1() = block {
        val (persistence, broker) = setupPersistence()
        val pub = PublishMessage("test", QualityOfService.AT_LEAST_ONCE, payload = buffer)
        val packetId = persistence.writePubGetPacketId(broker, pub)
        val expectedPub = pub.copy(fixed = pub.fixed.copy(dup = true)).maybeCopyWithNewPacketIdentifier(packetId)
        val queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size)
        val queuedPacket = queuedPackets.first()
        assertEquals(expectedPub, queuedPacket)
        persistence.ackPub(broker, PublishAcknowledgment(packetId))
        assertEquals(0, persistence.messagesToSendOnReconnect(broker).size)
    }

    @Test
    fun pubQos2() = block {
        val (persistence, broker) = setupPersistence()
        val pub = PublishMessage("test", QualityOfService.EXACTLY_ONCE, payload = buffer)

        val packetId = persistence.writePubGetPacketId(broker, pub)
        val expectedPub = pub.copy(fixed = pub.fixed.copy(dup = true)).maybeCopyWithNewPacketIdentifier(packetId)
        var queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "queued pub")
        var queuedPacket = queuedPackets.first()
        assertEquals(expectedPub, queuedPacket)

        val pubRel = PublishRelease(packetId)
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
        val pub = PublishMessage("test", QualityOfService.EXACTLY_ONCE, payload = buffer, packetIdentifier = packetId)
        val pubRecv = pub.expectedResponse() as PublishReceived

        persistence.incomingPublish(broker, pub, pubRecv)
        var queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "incoming publish")
        var queuedPacket = queuedPackets.first()
        assertEquals(pubRecv, queuedPacket)

        val pubRel = PublishRelease(packetId)
        val pubComp = PublishComplete(packetId)
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
        val sub = SubscribeRequest(NO_PACKET_ID, topicMap)

        val subWithPacketId = persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        var packetId = subWithPacketId.packetIdentifier
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
                packetId,
                listOf(ReasonCode.SUCCESS, ReasonCode.SUCCESS, ReasonCode.SUCCESS)
            )
        )
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size, "ackSub: ${queuedPackets.joinToString()}")

        val unsub = UnsubscribeRequest(NO_PACKET_ID, listOf("topic0", "topic1", "topic2"))

        packetId = persistence.writeUnsubGetPacketId(broker, unsub)
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(1, queuedPackets.size, "unsub: ${queuedPackets.joinToString()}")
        queuedPacket = queuedPackets.first()
        assertEquals(packetId, queuedPacket.packetIdentifier, "packetId")
        persistence.ackUnsub(broker, UnsubscribeAcknowledgment(packetId))
        queuedPackets = persistence.messagesToSendOnReconnect(broker)
        assertEquals(0, queuedPackets.size, "ackUnsub: ${queuedPackets.joinToString()}")
        subs = persistence.activeSubscriptions(broker)
        assertEquals(0, subs.size, "active subs: ${queuedPackets.joinToString()}")
    }

    @Test
    fun broker() = block {
        val p = newDefaultPersistence(inMemory = true)
        assertEquals(0, p.allBrokers().size, "initial broker size")
        val broker = p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt4)
        val allBrokers = p.allBrokers()
        assertEquals(1, allBrokers.size, "single broker size")
        assertEquals(broker.toString(), allBrokers.first().toString(), "broker match")
        p.removeBroker(broker.identifier)
        assertEquals(0, p.allBrokers().size, "empty broker")
    }

    fun clearMessages() = block {
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
            connectionTimeout = 10.seconds,
            websocketEndpoint = "/mqtt"

        )
        private val connectionRequestMqtt4 =
            ConnectionRequest(
                variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 1),
                payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt())
            )
    }
}
