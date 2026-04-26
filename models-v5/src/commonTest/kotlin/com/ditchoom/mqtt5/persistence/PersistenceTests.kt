package com.ditchoom.mqtt5.persistence

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishProperties
import com.ditchoom.mqtt5.controlpacket.PublishReceived
import com.ditchoom.mqtt5.controlpacket.PublishRelease
import com.ditchoom.mqtt5.controlpacket.SubscribeAcknowledgement
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeAcknowledgment
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
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
            p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt5),
        )
    }

    @Test
    fun pubQos1() =
        runTest {
            val (persistence, broker) = setupPersistence()
            buffer.position(0)
            val pub =
                ControlPacketV5.Publish.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = buffer,
                    properties =
                        PublishProperties(
                            messageExpiryInterval = 5L,
                            topicAlias = 1,
                            userProperty = listOf(Pair("Rahul", "Behera")),
                            subscriptionIdentifier = setOf(5L, 2L),
                        ),
                )
            val packetId = persistence.writePubGetPacketId(broker, pub)
            // Persistence encode advances pub.payload's position to the end; reset so the
            // equality check compares all 4 bytes on both sides.
            buffer.position(0)
            assertEquals(
                pub.maybeCopyWithNewPacketIdentifier(packetId),
                persistence.getPubWithPacketId(broker, packetId),
                "get packet",
            )
            buffer.position(0)
            val expectedPub = pub.setDupFlagNewPubMessage().maybeCopyWithNewPacketIdentifier(packetId)
            val queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(1, queuedPackets.size)
            val queuedPacket = queuedPackets.first()
            assertEquals(expectedPub, queuedPacket, "reconnected message")
            persistence.ackPub(broker, PublishAcknowledgment(packetId))
            assertEquals(0, persistence.messagesToSendOnReconnect(broker).size, "reconnect size")
        }

    @Test
    fun pubQos2() =
        runTest {
            val (persistence, broker) = setupPersistence()
            buffer.position(0)
            val pub =
                ControlPacketV5.Publish.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.EXACTLY_ONCE,
                    payload = buffer,
                    properties =
                        PublishProperties(
                            userProperty = listOf(Pair("Rahul", "Behera")),
                        ),
                )

            val packetId = persistence.writePubGetPacketId(broker, pub)
            buffer.position(0)
            val expectedPub = pub.setDupFlagNewPubMessage().maybeCopyWithNewPacketIdentifier(packetId)
            assertEquals(
                pub.maybeCopyWithNewPacketIdentifier(packetId),
                persistence.getPubWithPacketId(broker, packetId),
                "get packet",
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
    fun incomingQos1() =
        runTest {
            val (persistence, broker) = setupPersistence()
            buffer.position(0)
            val packetId = 2
            val pub =
                ControlPacketV5.Publish.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = buffer,
                    packetIdentifier = packetId,
                    properties =
                        PublishProperties(
                            userProperty = listOf(Pair("Rahul", "Behera")),
                        ),
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
            buffer.position(0)
            val packetId = 3
            val pub =
                ControlPacketV5.Publish.ofRaw(
                    topic = TopicName.fromOrThrow("test"),
                    qos = QualityOfService.EXACTLY_ONCE,
                    payload = buffer,
                    packetIdentifier = packetId,
                    properties =
                        PublishProperties(
                            userProperty = listOf(Pair("Rahul", "Behera")),
                        ),
                )

            persistence.persistIncomingPublish(broker, pub)
            var pending = persistence.incomingMessagesToRedispatch(broker)
            assertEquals(1, pending.size, "persisted incoming QoS 2")
            assertEquals(Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER, pending.first().state)

            persistence.incomingHandlerComplete(broker, packetId)
            pending = persistence.incomingMessagesToRedispatch(broker)
            assertEquals(1, pending.size, "QoS 2 stays on disk after handler, state transitions")
            assertEquals(Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT, pending.first().state)

            val pubComp = PublishComplete(packetId)
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
            val subscriptions =
                setOf(
                    Subscription(
                        topic0,
                        QualityOfService.AT_MOST_ONCE,
                        noLocal = false,
                        retainAsPublished = true,
                        retainHandling = ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES,
                    ),
                    Subscription(
                        topic1,
                        QualityOfService.AT_LEAST_ONCE,
                        noLocal = true,
                        retainAsPublished = false,
                        retainHandling = ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
                    ),
                    Subscription(
                        topic2,
                        QualityOfService.EXACTLY_ONCE,
                        noLocal = true,
                        retainAsPublished = false,
                        retainHandling = ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS,
                    ),
                )
            val sub =
                SubscribeRequest(
                    packetIdentifier = NO_PACKET_ID.toUShort(),
                    subscriptions = subscriptions,
                    reasonString = "testReason",
                    userProperty = listOf(Pair("Rahul", "Behera")),
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
                    payload = listOf(ReasonCode.GRANTED_QOS_0, ReasonCode.GRANTED_QOS_1, ReasonCode.GRANTED_QOS_2),
                ),
            )
            queuedPackets = persistence.messagesToSendOnReconnect(broker)
            assertEquals(0, queuedPackets.size, "ackSub: ${queuedPackets.joinToString()}")

            val unsub =
                UnsubscribeRequest(
                    topics = setOf(topic0, topic1, topic2),
                    userProperty = listOf(Pair("Rahul", "Behera")),
                )
            packetId = persistence.writeUnsubGetPacketId(broker, unsub)
            assertEquals(
                unsub.copyWithNewPacketIdentifier(packetId),
                persistence.getUnsubWithPacketId(broker, packetId),
                "get unsub",
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
                    reasonCodes = listOf(ReasonCode.SUCCESS, ReasonCode.SUCCESS, ReasonCode.SUCCESS),
                ),
            )
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
            val broker = p.addBroker(setOf(testMqttConnectionOptions, testWsMqttConnectionOptions), connectionRequestMqtt5)
            assertEquals(broker, p.brokerWithId(broker.identifier))
            val allBrokers = p.allBrokers()
            assertEquals(1, allBrokers.size, "single broker size")
            assertEquals(broker.connectionOps, allBrokers.first().connectionOps.toSet(), "broker match connection ops")
            assertEquals(broker.connectionRequest, allBrokers.first().connectionRequest, "broker match connection request")
            assertEquals(broker.identifier, allBrokers.first().identifier, "broker match id")
            p.removeBroker(broker.identifier)
            assertEquals(0, p.allBrokers().size, "empty broker")
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
                websocketEndpoint = "/mqtt",
                connectionTimeout = 10.seconds,
            )
        private val connectionRequestMqtt5 =
            ConnectionRequest(
                clientId = "taco123-" + Random.nextUInt(),
                keepAliveSeconds = 1,
                cleanStart = true,
                will = com.ditchoom.mqtt.controlpacket.WillConfig.Enabled(
                    topic = TopicName.fromOrThrow("testWill"),
                    payload = BufferFactory.Default.allocate(0),
                    qos = com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE,
                    retain = false,
                ),
                props = com.ditchoom.mqtt5.controlpacket.ConnectProperties(
                    sessionExpiryIntervalSeconds = 1u,
                    receiveMaximum = 500,
                    maximumPacketSize = 10_000_000uL,
                    topicAliasMaximum = 40,
                    requestProblemInformation = true,
                    userProperty = listOf(Pair("Rahul", "Behera"), Pair("yolo", "swag")),
                ),
                willProperties = com.ditchoom.mqtt5.controlpacket.ConnectWillProperties(
                    willDelayIntervalSeconds = 1,
                    correlationData = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4)).also { it.position(0) },
                    userProperty = listOf(Pair("will", "test"), Pair("test", "will")),
                ),
            )
    }
}
