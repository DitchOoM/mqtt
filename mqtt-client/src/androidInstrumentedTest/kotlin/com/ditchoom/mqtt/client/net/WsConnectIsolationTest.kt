package com.ditchoom.mqtt.client.net

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.net.defaultSingleConnection
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Isolates the WS hang surfaced by [com.ditchoom.mqtt.client.ipc.IPCTest.testIpcAllTypesOverWs].
 *
 * Runs the CONNECT/CONNACK handshake against the same Mosquitto container the IPC test
 * uses (`10.0.2.2:8080` on the emulator), but bypasses the entire IPC service — calls
 * `defaultConnectionFactory` directly in the test process. If this hangs the same way,
 * the bug is in the WS library's Android path. If this passes, the bug is somewhere
 * inside the AIDL/service plumbing for WS specifically.
 *
 * Bookend test [tcpConnectAndReceiveConnack] proves the same setup works for plain TCP,
 * so any WS-only failure isolates to WS.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class WsConnectIsolationTest {
    private val connectionRequest =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 5),
            payload = ConnectionRequest.Payload(clientId = "ws-isolation-" + Random.nextUInt()),
        )

    @Test
    fun tcpConnectAndReceiveConnack() =
        runBlocking(Dispatchers.Default) {
            withTimeout(15.seconds) {
                val options =
                    MqttConnectionOptions.SocketConnection(
                        host = "10.0.2.2",
                        port = 1883,
                        tlsEnabled = false,
                        connectionTimeout = 10.seconds,
                    )
                val conn = defaultSingleConnection(options, connectionRequest.controlPacketFactory)
                try {
                    conn.send(connectionRequest)
                    val connack = conn.receive().first()
                    assertTrue(connack is IConnectionAcknowledgment, "expected CONNACK, got ${connack::class.simpleName}")
                    assertTrue(connack.isSuccessful, "CONNACK rejected: ${connack.connectionReason}")
                } finally {
                    conn.close()
                }
            }
        }

    @Test
    fun wsConnectAndReceiveConnack() =
        runBlocking(Dispatchers.Default) {
            withTimeout(15.seconds) {
                val options =
                    MqttConnectionOptions.WebSocketConnectionOptions(
                        host = "10.0.2.2",
                        port = 8080,
                        websocketEndpoint = "/",
                        tlsEnabled = false,
                        protocols = listOf("mqtt"),
                        connectionTimeout = 10.seconds,
                    )
                val conn = defaultSingleConnection(options, connectionRequest.controlPacketFactory)
                try {
                    conn.send(connectionRequest)
                    val connack = conn.receive().first()
                    assertTrue(connack is IConnectionAcknowledgment, "expected CONNACK, got ${connack::class.simpleName}")
                    assertTrue(connack.isSuccessful, "CONNACK rejected: ${connack.connectionReason}")
                } finally {
                    conn.close()
                }
            }
        }

    /**
     * Bisects between the raw-WS path (proven to work in [wsConnectAndReceiveConnack]) and
     * the IPC test (hangs). This goes through `MqttClient` + `ConnectivityManager` —
     * exactly what the server-process side of the IPC service does — but runs in-process
     * with no AIDL boundary. If this hangs, the bug is in CM/MqttClient for WS.
     * If it passes, the bug isolates to the IPC layer (AIDL/persistence/observer wiring).
     */
    @Test
    fun wsMqttClientAwaitConnectivity() =
        runBlocking(Dispatchers.Default) {
            withTimeout(15.seconds) {
                val options =
                    MqttConnectionOptions.WebSocketConnectionOptions(
                        host = "10.0.2.2",
                        port = 8080,
                        websocketEndpoint = "/",
                        tlsEnabled = false,
                        protocols = listOf("mqtt"),
                        connectionTimeout = 10.seconds,
                    )
                val broker = MqttBroker(0, listOf(options), connectionRequest)
                val scope = CoroutineScope(Dispatchers.Default + CoroutineName("ws-isolation"))
                val client =
                    MqttClient.start(
                        scope = scope,
                        broker = broker,
                        persistence = InMemoryPersistence(),
                        connectSingle = defaultSingleConnection(broker),
                    )
                try {
                    val connack = client.awaitConnectivity()
                    assertTrue(connack.isSuccessful, "CONNACK rejected: ${connack.connectionReason}")
                } finally {
                    client.shutdown(sendDisconnect = false)
                    scope.cancel()
                }
            }
        }
}
