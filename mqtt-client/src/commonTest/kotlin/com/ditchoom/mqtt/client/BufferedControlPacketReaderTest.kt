package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PingResponse
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Creates a mock [MqttTransport] backed by an [AutoFillingSuspendingStreamProcessor]
 * that refills from a [Channel] of byte chunks.
 */
private class MockMqttTransport(
    private val chunks: Channel<ReadBuffer>,
) : MqttTransport {
    private var open = true
    private val pool = BufferPool()

    override val stream: AutoFillingSuspendingStreamProcessor =
        StreamProcessor.builder(pool).buildSuspendingWithAutoFill { autoFiller ->
            val chunk =
                chunks.receiveCatching().getOrNull()
                    ?: throw EndOfStreamException()
            autoFiller.append(chunk)
        }

    override fun isOpen(): Boolean = open

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = buffer.remaining()

    override suspend fun close() {
        open = false
        chunks.close()
    }
}

/**
 * Serializes a control packet to bytes and splits into chunks of [chunkSize].
 * Sends each chunk to the [channel].
 */
private fun sendPacketInChunks(
    packet: com.ditchoom.mqtt.controlpacket.ControlPacket,
    channel: Channel<ReadBuffer>,
    chunkSize: Int = Int.MAX_VALUE,
) {
    val buf = packet.serialize()
    buf.resetForRead()
    val bytes = buf.readByteArray(buf.remaining())

    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + chunkSize, bytes.size)
        val chunk = BufferFactory.Default.allocate(end - offset)
        for (i in offset until end) {
            chunk.writeByte(bytes[i])
        }
        chunk.resetForRead()
        channel.trySend(chunk)
        offset = end
    }
}

class BufferedControlPacketReaderTest {
    private val factory = ControlPacketV4Factory
    private val topic = TopicName.fromOrThrow("test/topic")

    @Test
    fun parseSingleConnackPacket() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            val connack = ConnectionAcknowledgment()
            sendPacketInChunks(connack, chunks)
            chunks.close()

            val packet = reader.readControlPacket()
            assertIs<ConnectionAcknowledgment>(packet)
            assertTrue(packet.isSuccessful)
        }

    @Test
    fun parseFragmentedPacketOneByteAtATime() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            val payload = BufferFactory.Default.allocate(10)
            repeat(10) { payload.writeByte(it.toByte()) }
            payload.resetForRead()
            val publish = PublishMessage.buildPayload(topicName = topic, payload = payload)

            // Send one byte at a time to exercise auto-fill
            sendPacketInChunks(publish, chunks, chunkSize = 1)
            chunks.close()

            val packet = reader.readControlPacket()
            assertIs<PublishMessage>(packet)
            assertEquals(topic.toString(), packet.variable.topicName.toString())
        }

    @Test
    fun parseZeroLengthPayloadPingReq() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            sendPacketInChunks(PingRequest, chunks)
            chunks.close()

            val packet = reader.readControlPacket()
            assertIs<com.ditchoom.mqtt3.controlpacket.PingRequest>(packet)
        }

    @Test
    fun parseMultiplePacketsFromFlow() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            // Send 3 packets
            sendPacketInChunks(PingRequest, chunks)
            sendPacketInChunks(PingResponse, chunks)
            sendPacketInChunks(ConnectionAcknowledgment(), chunks)
            chunks.close()

            val packets = reader.incomingControlPackets.toList()
            assertEquals(3, packets.size)
            assertIs<com.ditchoom.mqtt3.controlpacket.PingRequest>(packets[0])
            assertIs<com.ditchoom.mqtt3.controlpacket.PingResponse>(packets[1])
            assertIs<ConnectionAcknowledgment>(packets[2])
        }

    @Test
    fun endOfStreamCompletesFlowCleanly() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            // Send one packet then close (EOF)
            sendPacketInChunks(PingRequest, chunks)
            chunks.close()

            val packets = reader.incomingControlPackets.toList()
            assertEquals(1, packets.size)
            assertIs<com.ditchoom.mqtt3.controlpacket.PingRequest>(packets[0])
        }

    @Test
    fun largePayloadSpanningManyAutoFillCycles() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            val payloadSize = 4096
            val payload = BufferFactory.Default.allocate(payloadSize)
            repeat(payloadSize) { payload.writeByte((it % 256).toByte()) }
            payload.resetForRead()
            val publish = PublishMessage.buildPayload(topicName = topic, payload = payload)

            // Send in 64-byte chunks to exercise many auto-fill cycles
            sendPacketInChunks(publish, chunks, chunkSize = 64)
            chunks.close()

            val packet = reader.readControlPacket()
            assertIs<PublishMessage>(packet)
            assertEquals(topic.toString(), packet.variable.topicName.toString())
        }

    @Test
    fun variableByteIntegerMultiByteEncoding() =
        runTest {
            val chunks = Channel<ReadBuffer>(Channel.UNLIMITED)
            val transport = MockMqttTransport(chunks)
            val reader =
                BufferedControlPacketReader(
                    brokerId = 1,
                    factory = factory,
                    transport = transport,
                    incomingMessage = { _, _, _ -> },
                )

            // PUBLISH with payload large enough to require 2-byte variable-byte integer (>= 128 bytes)
            val payloadSize = 200
            val payload = BufferFactory.Default.allocate(payloadSize)
            repeat(payloadSize) { payload.writeByte((it % 256).toByte()) }
            payload.resetForRead()
            val publish = PublishMessage.buildPayload(topicName = topic, payload = payload)

            sendPacketInChunks(publish, chunks)
            chunks.close()

            val packet = reader.readControlPacket()
            assertIs<PublishMessage>(packet)
        }
}
