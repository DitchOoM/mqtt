package com.ditchoom.mqtt.client.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.runner.AndroidJUnit4
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.client.net.sendAllMessageTypes
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@MediumTest
class IPCTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule.withTimeout(15, TimeUnit.SECONDS)

    private val connectionRequestMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 5),
            payload = ConnectionRequest.Payload(clientId = "taco123Ipc-" + Random.nextUInt()),
        )

    private suspend fun bindAndCreateService(): MqttService {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val i = Intent(context, MqttManagerService::class.java)
        val serviceBinder =
            suspendCancellableCoroutine { cont ->
                val connection =
                    object : ServiceConnection {
                        override fun onServiceConnected(
                            name: ComponentName,
                            service: IBinder,
                        ) {
                            Log.i("IPCTest", "onServiceConnected: $name")
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            Log.w("IPCTest", "onServiceDisconnected: $name")
                            if (cont.isActive) cont.resumeWithException(RuntimeException("Service disconnected: $name"))
                        }
                    }
                Log.i("IPCTest", "Calling serviceRule.bindService (3-arg)")
                serviceRule.bindService(i, connection, Context.BIND_AUTO_CREATE)
                Log.i("IPCTest", "serviceRule.bindService returned")
            }
        val clientSideService =
            LocalMqttService.buildService(
                connectionFactory = { _ ->
                    { _ -> throw UnsupportedOperationException("Client proxy does not create connections directly") }
                },
                androidContext = context,
            )
        return AndroidRemoteMqttServiceClient(serviceBinder, clientSideService)
    }

    private suspend fun runIpcAllTypes(connectionOptions: MqttConnectionOptions) {
        val service = bindAndCreateService()
        service.allBrokers().forEach { service.removeBroker(it.brokerId, it.protocolVersion) }

        val broker = service.addBroker(listOf(connectionOptions), connectionRequestMqtt4)
        service.start(broker)
        val client = checkNotNull(service.getClient(broker))
        client.awaitConnectivity()
        sendAllMessageTypes(client, TopicName.fromOrThrow("testIpc"), "Test String")
        client.shutdown(true)
    }

    @Test
    fun testIpcAllTypesOverTcp() =
        runBlocking(Dispatchers.Default) {
            runIpcAllTypes(
                MqttConnectionOptions.SocketConnection(
                    "10.0.2.2",
                    1883,
                    tlsEnabled = false,
                    connectionTimeout = 10.seconds,
                ),
            )
        }

    @Test
    fun testIpcAllTypesOverWs() =
        runBlocking(Dispatchers.Default) {
            runIpcAllTypes(
                MqttConnectionOptions.WebSocketConnectionOptions(
                    "10.0.2.2",
                    8080,
                    websocketEndpoint = "/",
                    tlsEnabled = false,
                    protocols = listOf("mqtt"),
                    connectionTimeout = 10.seconds,
                ),
            )
        }
}
