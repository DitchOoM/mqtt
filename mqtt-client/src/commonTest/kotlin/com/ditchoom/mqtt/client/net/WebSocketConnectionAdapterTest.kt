package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.mapNotNull
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.mqtt3.controlpacket.DisconnectNotification
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.websocket.WebSocketMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketConnectionAdapterTest {
    private class FakeWsConnection(
        val outbound: Channel<WebSocketMessage<ControlPacket>>,
        val inbound: Channel<WebSocketMessage<ControlPacket>>,
        override val id: Long = 0,
    ) : Connection<WebSocketMessage<ControlPacket>> {
        var closed = false

        override suspend fun send(message: WebSocketMessage<ControlPacket>) = outbound.send(message)

        override fun receive(): Flow<WebSocketMessage<ControlPacket>> = inbound.receiveAsFlow()

        override suspend fun close() {
            closed = true
            outbound.close()
            inbound.close()
        }
    }

    private fun fakePair(): FakeWsConnection =
        FakeWsConnection(
            outbound = Channel(Channel.UNLIMITED),
            inbound = Channel(Channel.UNLIMITED),
        )

    private fun adapt(
        ws: FakeWsConnection,
        factory: ControlPacketFactory = ControlPacketV4Factory,
    ): Connection<ControlPacket> =
        ws.mapNotNull(
            encode = { packet: ControlPacket -> WebSocketMessage.Binary(packet) },
            decode = { message: WebSocketMessage<ControlPacket> ->
                when (message) {
                    is WebSocketMessage.Binary -> message.payload
                    else -> null
                }
            },
        )

    @Test
    fun sendWrapsControlPacketIntoBinaryFrame() =
        runTest {
            val ws = fakePair()
            val adapted = adapt(ws)

            adapted.send(PingRequest())

            val emitted = ws.outbound.receive()
            assertTrue(emitted is WebSocketMessage.Binary, "Expected Binary frame, got ${emitted::class.simpleName}")
            assertTrue(emitted.payload is PingRequest, "Expected PingRequest payload")
        }

    @Test
    fun receiveSurfacesBinaryFrameAsControlPacket() =
        runTest {
            val ws = fakePair()
            val adapted = adapt(ws)

            ws.inbound.send(WebSocketMessage.Binary(DisconnectNotification()))

            val received = adapted.receive().first()
            assertTrue(received is DisconnectNotification, "Expected DisconnectNotification, got ${received::class.simpleName}")
        }

    @Test
    fun nonBinaryFramesAreDropped() =
        runTest {
            val ws = fakePair()
            val adapted = adapt(ws)

            ws.inbound.send(WebSocketMessage.Text("ignored"))
            ws.inbound.send(WebSocketMessage.Ping())
            ws.inbound.send(WebSocketMessage.Pong())
            ws.inbound.send(WebSocketMessage.Binary(PingRequest()))
            ws.inbound.send(WebSocketMessage.Close(code = 1000u, reason = ""))

            val received = adapted.receive().take(1).toList()
            assertEquals(1, received.size)
            assertTrue(received.single() is PingRequest)
        }

    @Test
    fun closeDelegatesToUnderlyingConnection() =
        runTest {
            val ws = fakePair()
            val adapted = adapt(ws)

            assertFalse(ws.closed)
            adapted.close()
            assertTrue(ws.closed)
        }
}
