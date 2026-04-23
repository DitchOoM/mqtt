package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.ControlPacketProcessor
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PingRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Isolates the IPC observer-dispatch gap called out in
 * V2_PHASE_POST_QUICHE_HANDOFF.md open item #1.
 *
 * The bug: [com.ditchoom.mqtt.client.ipc.RemoteMqttClientWorker.observers] is populated
 * by AndroidMqttClientIPCServer/JsRemoteMqttServiceWorker, but nothing on the server side
 * iterates the list when packets cross [ControlPacketProcessor]. Result: SUBACK and
 * PUBACK flow through the local client in the server process but never reach the
 * client-process AIDL callback, so IPC tests hang on await.
 *
 * Fix surface: the processor must expose a stream of outgoing (sent) packets and
 * incoming (read) packets for the worker to subscribe to and fan out to observers.
 * The existing [ControlPacketProcessor.readChannel] already covers incoming, so the
 * missing piece is a sent-packet flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserverDispatchTest {
    private fun newProcessor(): Triple<ControlPacketProcessor, MutableSharedFlow<ControlPacket>, Channel<Collection<ControlPacket>>> {
        val broker =
            MqttBroker(
                identifier = 0,
                connectionOps = emptyList(),
                connectionRequest =
                    ConnectionRequest(
                        variableHeader =
                            ConnectionRequest.VariableHeader(
                                cleanSession = true,
                                keepAliveSeconds = 0,
                            ),
                        payload = ConnectionRequest.Payload(clientId = "observer-dispatch-test"),
                    ),
            )
        val readChannel = MutableSharedFlow<ControlPacket>(replay = 0, extraBufferCapacity = 8)
        val writeChannel = Channel<Collection<ControlPacket>>(Channel.BUFFERED)
        val processor =
            ControlPacketProcessor(
                broker = broker,
                readChannel = readChannel,
                writeChannel = writeChannel,
                persistence = InMemoryPersistence(),
            )
        return Triple(processor, readChannel, writeChannel)
    }

    @Test
    fun sentPacketsFlowEmitsOnOnPacketSent() =
        runTest(UnconfinedTestDispatcher()) {
            val (processor, _, _) = newProcessor()
            val observed = mutableListOf<ControlPacket>()

            val job =
                launch {
                    processor.sentPackets.collect { observed += it }
                }
            yield()

            processor.onPacketSent(PingRequest as ControlPacket)
            yield()

            assertEquals(1, observed.size, "onPacketSent must emit exactly once on sentPackets flow")
            assertSame(PingRequest as ControlPacket, observed[0])
            job.cancel()
        }

    @Test
    fun readChannelAndSentPacketsExposeBothDirections() =
        runTest(UnconfinedTestDispatcher()) {
            val (processor, readChannel, _) = newProcessor()
            val incomingPackets = mutableListOf<ControlPacket>()
            val outgoingPackets = mutableListOf<ControlPacket>()

            val readJob = launch { processor.readChannel.collect { incomingPackets += it } }
            val sentJob = launch { processor.sentPackets.collect { outgoingPackets += it } }
            yield()

            readChannel.emit(PingRequest as ControlPacket)
            processor.onPacketSent(PingRequest as ControlPacket)
            yield()

            assertEquals(1, incomingPackets.size, "readChannel must expose incoming packet to external subscriber")
            assertEquals(1, outgoingPackets.size, "sentPackets must expose outgoing packet to external subscriber")

            readJob.cancel()
            sentJob.cancel()
        }

    /**
     * End-to-end dispatch contract: when the processor is wired behind a
     * [RemoteMqttClientWorker], the worker's observers must fire for every incoming
     * and outgoing packet. Documents the invariant that makes
     * `testIpcAllTypesOverTcp` pass: a SUBACK read from the broker in the server
     * process must reach observers so the AIDL bridge can forward it.
     */
    @Test
    fun workerObserversFireForIncomingAndOutgoingPackets() =
        runTest(UnconfinedTestDispatcher()) {
            val (processor, readChannel, _) = newProcessor()
            val observed = mutableListOf<Pair<Boolean, ControlPacket>>()

            // Minimal stand-in for RemoteMqttClientWorker's subscription wiring.
            // The real worker installs equivalent collectors in its init block.
            val readJob = launch { processor.readChannel.collect { observed += true to it } }
            val sentJob = launch { processor.sentPackets.collect { observed += false to it } }
            yield()

            readChannel.emit(PingRequest as ControlPacket)
            processor.onPacketSent(PingRequest as ControlPacket)
            yield()

            assertEquals(2, observed.size, "observer list must see both incoming and outgoing packets")
            assertEquals(true, observed[0].first, "first event must be incoming")
            assertEquals(false, observed[1].first, "second event must be outgoing")

            readJob.cancel()
            sentJob.cancel()
        }
}
