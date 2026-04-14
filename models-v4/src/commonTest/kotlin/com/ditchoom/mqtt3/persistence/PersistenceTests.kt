package com.ditchoom.mqtt3.persistence

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubscribeAcknowledgement
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.UnsubscribeAcknowledgment
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PersistenceTests {
    private val buffer = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))

    private suspend fun setupPersistence(): Pair<Persistence, MqttBroker> {
        val p = newDefaultPersistence(name = "test" + Random.nextUInt(), inMemory = true)
        val b = p.allBrokers()
        if (b.isNotEmpty()) {
            return Pair(p, b.first())
        }
        return Pair(
            p,
            p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt4),
        )
    }

    @Test
    fun pubQos1() =
        runTest {
            val (persistence, broker) = setupPersistence()
            val pub =
                PublishMessageV4.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = buffer,
                )
            val packetId = persistence.writePubGetPacketId(broker, pub)
            assertEquals(
                pub.maybeCopyWithNewPacketIdentifier(packetId),
                persistence.getPubWithPacketId(broker, packetId),
                "get packet",
            )
            val expectedPub = pub.setDupFlagNewPubMessage().maybeCopyWithNewPacketIdentifier(packetId)
            val queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(1, queuedPackets.size)
            val queuedPacket = queuedPackets.first()
            assertEquals(expectedPub, queuedPacket)
            persistence.ackPub(broker, PublishAcknowledgment(packetId.toUShort()))
            assertEquals(0, persistence.messagesToSendOnReconnect(broker).size)
        }

    @Test
    fun pubQos2() =
        runTest {
            val (persistence, broker) = setupPersistence()
            val pub =
                PublishMessageV4.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.EXACTLY_ONCE,
                    payload = buffer,
                )
            val packetId = persistence.writePubGetPacketId(broker, pub)
            assertEquals(
                pub.maybeCopyWithNewPacketIdentifier(packetId),
                persistence.getPubWithPacketId(broker, packetId),
                "get packet",
            )
            val expectedPub = pub.setDupFlagNewPubMessage().maybeCopyWithNewPacketIdentifier(packetId)
            var queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(1, queuedPackets.size, "queued pub")
            var queuedPacket = queuedPackets.first()
            assertEquals(expectedPub, queuedPacket)

            val pubRel = PublishRelease(packetId.toUShort())
            persistence.ackPubReceivedQueuePubRelease(broker, PublishReceived(packetId.toUShort()), pubRel)
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(1, queuedPackets.size, "queued pub rel ${queuedPackets.joinToString()}")
            queuedPacket = queuedPackets.first()
            assertEquals(pubRel, queuedPacket)

            persistence.ackPubComplete(broker, PublishComplete(packetId.toUShort()))
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(0, queuedPackets.size)
        }

    @Test
    fun incomingQos1() =
        runTest {
            val (persistence, broker) = setupPersistence()
            val packetId = 2
            val pub =
                PublishMessageV4.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = buffer,
                    packetIdentifier = packetId,
                )

            persistence.persistIncomingPublish(broker, pub)
            val pending = persistence.incomingMessagesToRedispatch(broker)
            assertEquals(1, pending.size, "persisted incoming QoS 1")
            assertEquals(Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER, pending.first().state)
            assertEquals(packetId, pending.first().packet.packetIdentifier)

            persistence.incomingHandlerComplete(broker, packetId)
            assertEquals(0, persistence.incomingMessagesToRedispatch(broker).size, "QoS 1 deleted on handler complete")
        }

    @Test
    fun incomingQos2() =
        runTest {
            val (persistence, broker) = setupPersistence()
            val packetId = 3
            val pub =
                PublishMessageV4.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.EXACTLY_ONCE,
                    payload = buffer,
                    packetIdentifier = packetId,
                )

            persistence.persistIncomingPublish(broker, pub)
            var pending = persistence.incomingMessagesToRedispatch(broker)
            assertEquals(1, pending.size, "persisted incoming QoS 2")
            assertEquals(Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER, pending.first().state)

            persistence.incomingHandlerComplete(broker, packetId)
            pending = persistence.incomingMessagesToRedispatch(broker)
            assertEquals(1, pending.size, "QoS 2 stays on disk after handler, state transitions")
            assertEquals(Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT, pending.first().state)

            val pubComp = PublishComplete(packetId.toUShort())
            persistence.onPubCompWritten(broker, pubComp)
            assertEquals(0, persistence.incomingMessagesToRedispatch(broker).size, "row deleted after pub comp written")
        }

    @Test
    fun subscription() =
        runTest {
            val (persistence, broker) = setupPersistence()
            val topicMap = HashMap<TopicFilter, QualityOfService>()
            val topic0 = TopicFilter.fromOrThrow("topic0")
            val topic1 = TopicFilter.fromOrThrow("topic1")
            val topic2 = TopicFilter.fromOrThrow("topic2")
            topicMap[topic0] = QualityOfService.AT_MOST_ONCE
            topicMap[topic1] = QualityOfService.AT_LEAST_ONCE
            topicMap[topic2] = QualityOfService.EXACTLY_ONCE
            val sub = SubscribeRequest(NO_PACKET_ID, topicMap)

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
                    packetId,
                    listOf(ReasonCode.SUCCESS, ReasonCode.SUCCESS, ReasonCode.SUCCESS),
                ),
            )
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(0, queuedPackets.size, "ackSub: ${queuedPackets.joinToString()}")

            val unsub = UnsubscribeRequest(NO_PACKET_ID, listOf("topic0", "topic1", "topic2"))
            packetId = persistence.writeUnsubGetPacketId(broker, unsub)
            assertEquals(
                unsub.copyWithNewPacketIdentifier(packetId),
                persistence.getUnsubWithPacketId(broker, packetId),
                "get unsub",
            )
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(1, queuedPackets.size, "unsub: ${queuedPackets.joinToString()}")
            queuedPacket = queuedPackets.first()
            assertEquals(packetId, queuedPacket.packetIdentifier, "packetId")
            persistence.ackUnsub(broker, UnsubscribeAcknowledgment(packetId.toUShort()))
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(0, queuedPackets.size, "ackUnsub: ${queuedPackets.joinToString()}")
            subs = persistence.activeSubscriptions(broker)
            assertEquals(0, subs.size, "active subs: ${queuedPackets.joinToString()}")
        }

    @Test
    fun broker() =
        runTest {
            val p = newDefaultPersistence(inMemory = true)
            assertEquals(0, p.allBrokers().size, "initial broker size")
            val broker = p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt4)
            assertEquals(broker, p.brokerWithId(broker.identifier), "broker with id")
            val allBrokers = p.allBrokers()
            assertEquals(1, allBrokers.size, "single broker size")
            assertEquals(broker.toString(), allBrokers.first().toString(), "broker match")
            p.removeBroker(broker.identifier)
            assertEquals(0, p.allBrokers().size, "empty broker")
        }

    fun clearMessages() =
        runTest {
        }

    companion object {
        private val testMqttConnectionOptions =
            MqttConnectionOptions.SocketConnection(
                "localhost",
                1883,
                tlsEnabled = false,
                connectionTimeout = 10.seconds,
            )
        private val testWsMqttConnectionOptions =
            MqttConnectionOptions.WebSocketConnectionOptions(
                "localhost",
                80,
                tlsEnabled = false,
                protocols = listOf("mqttv3.1"),
                connectionTimeout = 10.seconds,
                websocketEndpoint = "/mqtt",
            )
        private val connectionRequestMqtt4 =
            ConnectionRequest(
                variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 1),
                payload = ConnectionRequest.Payload(clientId = "taco123-" + Random.nextUInt()),
            )
    }
}
