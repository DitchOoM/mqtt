package com.ditchoom.mqtt.client

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.net.awaitPublishComplete
import com.ditchoom.mqtt.client.net.opaquePayloadUtf8
import com.ditchoom.mqtt.client.net.runTestNoTimeSkipping
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt5.controlpacket.ConnAckProperties
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Client-side send quota ([MQTT-3.3.4-8]/[MQTT-3.3.4-9]) and packet-identifier uniqueness
 * under concurrent QoS 1/2 publishing (DitchOoM/mqtt#12). A scripted MQTT 5 broker decodes
 * every outbound frame from its serialised bytes and records what it observed on the wire.
 */
class SendQuotaTest {
    @Test
    fun concurrentQos1AndQos2PublishesStayWithinReceiveMaximumAndDecodeIntact() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val broker = ScriptedBroker(this, listOf(Session.Acks(receiveMaximum = 4, sessionPresent = false)))
            val client = broker.startClient()
            val expected = mutableSetOf<String>()
            val publishes =
                (0 until 64).flatMap { worker ->
                    (0 until 8).map { n ->
                        val body = "p-$worker-$n"
                        expected += body
                        val qos = if ((worker + n) % 2 == 0) QualityOfService.AT_LEAST_ONCE else QualityOfService.EXACTLY_ONCE
                        async(Dispatchers.Default) { awaitPublishComplete(client.publish(TOPIC, qos, body.toReadBuffer(Charset.UTF8))) }
                    }
                }
            withTimeout(30.seconds) { publishes.awaitAll() }
            client.shutdown(sendDisconnect = false)
            val report = broker.reports.receive()
            assertEquals(emptyList(), report.violations)
            assertTrue(report.maxInFlight <= 4, "broker saw ${report.maxInFlight} in flight, Receive Maximum is 4")
            assertEquals(expected, report.payloads.toSet())
            assertEquals(1L, client.connectionCount())
        }

    @Test
    fun receiveMaximumFromLaterConnackIsHonoured() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val broker =
                ScriptedBroker(
                    this,
                    listOf(
                        Session.DropsOnFirstPublish(receiveMaximum = UShort.MAX_VALUE.toInt(), sessionPresent = false),
                        Session.Acks(receiveMaximum = 2, sessionPresent = true),
                    ),
                )
            val client = broker.startClient()
            val first = client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "first".toReadBuffer(Charset.UTF8))
            broker.reports.receive()
            val burst =
                (0 until 10).map { n ->
                    async(Dispatchers.Default) {
                        awaitPublishComplete(client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "b$n".toReadBuffer(Charset.UTF8)))
                    }
                }
            withTimeout(30.seconds) {
                burst.awaitAll()
                awaitPublishComplete(first)
            }
            client.shutdown(sendDisconnect = false)
            val report = broker.reports.receive()
            assertEquals(emptyList(), report.violations)
            assertTrue(report.maxInFlight <= 2, "broker saw ${report.maxInFlight} in flight, Receive Maximum is 2")
        }

    @Test
    fun quotaHeldByADiscardedSessionIsReturned() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val broker =
                ScriptedBroker(
                    this,
                    listOf(
                        Session.DropsOnFirstPublish(receiveMaximum = 1, sessionPresent = false),
                        Session.Acks(receiveMaximum = 1, sessionPresent = false),
                    ),
                )
            val client = broker.startClient()
            val lost = client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "lost".toReadBuffer(Charset.UTF8))
            broker.reports.receive()
            withTimeout(15.seconds) {
                awaitPublishComplete(client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "next".toReadBuffer(Charset.UTF8)))
            }
            assertEquals(QoS1State.SessionDiscarded, (lost as PublishResult.QoS1).state.value)
            client.shutdown(sendDisconnect = false)
            assertEquals(emptyList(), broker.reports.receive().violations)
        }

    @Test
    fun publishesIssuedWhileOfflineWaitForTheSessionQuota() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val broker =
                ScriptedBroker(
                    this,
                    listOf(Session.Acks(receiveMaximum = 2, sessionPresent = false)),
                    failedAttemptsFirst = 1,
                )
            val client = broker.startClient()
            val publishes =
                (0 until 6).map { n ->
                    async(Dispatchers.Default) {
                        awaitPublishComplete(client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "o$n".toReadBuffer(Charset.UTF8)))
                    }
                }
            withTimeout(30.seconds) { publishes.awaitAll() }
            client.shutdown(sendDisconnect = false)
            val report = broker.reports.receive()
            assertEquals(emptyList(), report.violations)
            assertTrue(report.maxInFlight <= 2, "broker saw ${report.maxInFlight} in flight, Receive Maximum is 2")
            assertEquals(1L, client.connectionCount())
        }

    /**
     * A publish the window admits while the old connection is dying (its event queued ahead of
     * SessionEnded) is persisted after the reconnect has already begun. The resumed session must
     * still resend it: the session snapshot has to be read in the window's own event order, not
     * by the reconnecting caller. The interleaving is pinned with the test scheduler and a gate
     * that holds the window inside that admission until the second CONNACK has been handled.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun publishAdmittedWhileTheOldSessionDiesIsResentOnResume() =
        runTest {
            val persistence = GatedPublishWrites(InMemoryPersistence())
            val request = ConnectionRequest(clientId = "send-quota", keepAliveSeconds = 60, cleanStart = false)
            val brokerRecord = persistence.addBroker(listOf(MqttConnectionOptions.SocketConnection("scripted", 1883)), request)
            val dying =
                ManualConnection(ControlPacketV5.ConnAck(properties = ConnAckProperties(receiveMaximum = UShort.MAX_VALUE.toInt())))
            val resumed =
                ManualConnection(ControlPacketV5.ConnAck(sessionPresent = true, properties = ConnAckProperties(receiveMaximum = 2)))
            val connections = ArrayDeque(listOf(dying, resumed))
            val client =
                MqttClient.start(
                    scope = backgroundScope,
                    broker = brokerRecord,
                    persistence = persistence,
                    timeSource = testScheduler.timeSource,
                    connectSingle = { _, _ -> connections.removeFirst() },
                )
            client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "first".toReadBuffer(Charset.UTF8))

            val admissionHeld = persistence.holdNextPublishWrite()
            val late =
                async { client.publish(TOPIC, QualityOfService.AT_LEAST_ONCE, "late".toReadBuffer(Charset.UTF8)) }
            runCurrent()
            dying.brokerCloses()
            runCurrent()
            admissionHeld.complete(Unit)
            runCurrent()

            val lateResult = late.await() as PublishResult.QoS1
            resumed.acknowledgeQos1Publishes()
            runCurrent()
            assertIs<QoS1State.Acknowledged>(lateResult.state.value, "late publish ended ${lateResult.state.value}")
            client.shutdown(sendDisconnect = false)
        }

    private companion object {
        const val TOPIC = "quota/test"
    }
}

/** Suspends the next [writePubGetPacketId] until the returned gate completes. */
private class GatedPublishWrites(
    private val inner: Persistence,
) : Persistence by inner {
    private val gates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    fun holdNextPublishWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates.trySend(it) }

    override suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: PublishMessage,
    ): Int {
        gates.tryReceive().onSuccess { it.await() }
        return inner.writePubGetPacketId(broker, pub)
    }
}

/** A connection the test drives by hand: [connack] is queued up front, everything sent is recorded. */
private class ManualConnection(
    connack: ControlPacket,
) : Connection<ControlPacket> {
    override val id: Long = 0L
    private val sent = Channel<ControlPacket>(Channel.UNLIMITED)
    private val inbound = Channel<ControlPacket>(Channel.UNLIMITED).also { it.trySend(connack) }

    override suspend fun send(message: ControlPacket) {
        sent.send(message)
    }

    override fun receive(): Flow<ControlPacket> =
        flow {
            for (packet in inbound) emit(packet)
        }

    override suspend fun close() {
        inbound.close()
    }

    fun brokerCloses() {
        inbound.close()
    }

    fun acknowledgeQos1Publishes() {
        generateSequence { sent.tryReceive().getOrNull() }
            .filterIsInstance<PublishMessage>()
            .filter { it.qualityOfService == QualityOfService.AT_LEAST_ONCE }
            .forEach { inbound.trySend(ControlPacketV5.PubAck(it.packetIdentifier)) }
    }
}

private sealed interface Session {
    val receiveMaximum: Int
    val sessionPresent: Boolean

    data class Acks(
        override val receiveMaximum: Int,
        override val sessionPresent: Boolean,
    ) : Session

    data class DropsOnFirstPublish(
        override val receiveMaximum: Int,
        override val sessionPresent: Boolean,
    ) : Session
}

private data class SessionReport(
    val maxInFlight: Int,
    val payloads: List<String>,
    val violations: List<String>,
)

/**
 * Hands out one [ScriptedConnection] per successful connect, following [sessions] in order.
 * Each connection is driven by a single broker coroutine, so the broker-side bookkeeping is
 * confined to that coroutine; its findings arrive on [reports] when the connection ends.
 */
private class ScriptedBroker(
    private val scope: CoroutineScope,
    private val sessions: List<Session>,
    private var failedAttemptsFirst: Int = 0,
) {
    val reports = Channel<SessionReport>(Channel.UNLIMITED)
    private var connections = 0

    suspend fun startClient(): MqttClient {
        val persistence = InMemoryPersistence()
        val request = ConnectionRequest(clientId = "send-quota", keepAliveSeconds = 60, cleanStart = false)
        val brokerRecord = persistence.addBroker(listOf(MqttConnectionOptions.SocketConnection("scripted", 1883)), request)
        return MqttClient.start(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.Default),
            broker = brokerRecord,
            persistence = persistence,
            connectSingle = { _, _, _ -> connect() },
        )
    }

    private fun connect(): Connection<ControlPacket> {
        if (failedAttemptsFirst > 0) {
            failedAttemptsFirst--
            throw ScriptedConnectRefused()
        }
        val session = sessions[minOf(connections++, sessions.lastIndex)]
        return ScriptedConnection(scope, session, reports)
    }
}

private class ScriptedConnectRefused : Exception()

private class ScriptedConnection(
    scope: CoroutineScope,
    private val session: Session,
    private val reports: Channel<SessionReport>,
) : Connection<ControlPacket> {
    override val id: Long = 0L
    private val wire = Channel<ReadBuffer>(Channel.UNLIMITED)
    private val inbound = Channel<ControlPacket>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) { serve() }
    }

    override suspend fun send(message: ControlPacket) {
        wire.send(message.serialize())
    }

    override fun receive(): Flow<ControlPacket> =
        flow {
            for (packet in inbound) emit(packet)
        }

    override suspend fun close() {
        wire.close()
        inbound.close()
    }

    private suspend fun serve() {
        val inFlight = LinkedHashMap<Int, QualityOfService>()
        val awaitingAck = ArrayDeque<ControlPacketV5.Publish<*>>()
        val payloads = mutableListOf<String>()
        val violations = mutableListOf<String>()
        var maxInFlight = 0
        var open = true
        while (open) {
            open =
                select {
                    wire.onReceiveCatching { result ->
                        val frame = result.getOrNull() ?: return@onReceiveCatching false
                        val packet =
                            runCatching { decodeV5Opaque(frame) }.getOrElse {
                                violations += "malformed frame: $it"
                                return@onReceiveCatching true
                            }
                        when (packet) {
                            is ControlPacketV5.Connect ->
                                inbound.send(
                                    ControlPacketV5.ConnAck(
                                        sessionPresent = session.sessionPresent,
                                        properties = ConnAckProperties(receiveMaximum = session.receiveMaximum),
                                    ),
                                )
                            is ControlPacketV5.Publish<*> -> {
                                val qos = packet.qualityOfService
                                if (qos != QualityOfService.AT_MOST_ONCE) {
                                    val id = packet.packetIdentifier
                                    if (id == 0) violations += "QoS ${qos.integerValue} PUBLISH with packet id 0"
                                    if (inFlight.put(id, qos) != null && !packet.dup) {
                                        violations += "packet id $id reused while in flight"
                                    }
                                    maxInFlight = maxOf(maxInFlight, inFlight.size)
                                    if (inFlight.size > session.receiveMaximum) {
                                        violations += "${inFlight.size} in flight exceeds Receive Maximum ${session.receiveMaximum}"
                                    }
                                }
                                packet.opaquePayloadUtf8()?.let { payloads += it }
                                when (session) {
                                    is Session.DropsOnFirstPublish -> {
                                        inbound.close()
                                        return@onReceiveCatching false
                                    }
                                    is Session.Acks -> if (qos != QualityOfService.AT_MOST_ONCE) awaitingAck.addLast(packet)
                                }
                            }
                            is ControlPacketV5.PubRel -> {
                                inFlight.remove(packet.packetIdentifier)
                                inbound.send(ControlPacketV5.PubComp(packet.packetIdentifier))
                            }
                            else -> Unit
                        }
                        true
                    }
                    if (awaitingAck.isNotEmpty()) {
                        onTimeout(2.milliseconds) {
                            val pub = awaitingAck.removeFirst()
                            when (pub.qualityOfService) {
                                QualityOfService.AT_LEAST_ONCE -> {
                                    inFlight.remove(pub.packetIdentifier)
                                    inbound.send(ControlPacketV5.PubAck(pub.packetIdentifier))
                                }
                                QualityOfService.EXACTLY_ONCE -> inbound.send(ControlPacketV5.PubRec(pub.packetIdentifier))
                                QualityOfService.AT_MOST_ONCE -> Unit
                            }
                            true
                        }
                    }
                }
        }
        reports.send(SessionReport(maxInFlight, payloads, violations))
    }
}
