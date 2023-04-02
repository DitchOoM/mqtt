package com.ditchoom.mqtt.client.ipc

import android.content.Context
import android.content.Intent
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.runner.AndroidJUnit4
import block
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.client.net.sendAllMessageTypes
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
@MediumTest
class IPCTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule.withTimeout(1000, TimeUnit.MILLISECONDS)
    private val testWsMqttConnectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
        "10.0.2.2",
        80,
        websocketEndpoint = "/mqtt",
        tls = false,
        protocols = listOf("mqttv3.1"),
        connectionTimeout = 10.seconds
    )
    private val connectionRequestMqtt4 =
        ConnectionRequest(
            variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 1),
            payload = ConnectionRequest.Payload(clientId = "taco123Ipc-" + Random.nextUInt())
        )

    @Test
    fun testIpcAllTypes() = block {
        val context = InstrumentationRegistry.getInstrumentation().context
        val i = Intent(context, MqttManagerService::class.java)
        val serviceBinder = suspendCancellableCoroutine {
            val connection = MqttServiceHelper.MqttServiceConnection(context, it)
            serviceRule.bindService(i, connection, Context.BIND_AUTO_CREATE)
        }
        // inMemory will not work because it's separate processes
        val service: MqttService = AndroidRemoteMqttServiceClient(serviceBinder, LocalMqttService.buildService(context))
        service.allBrokers().forEach { service.removeBroker(it.brokerId, it.protocolVersion) }

        val broker = service.addBroker(listOf(testWsMqttConnectionOptions), connectionRequestMqtt4)
        service.start(broker)
        val client = checkNotNull(service.getClient(broker))
        client.awaitConnectivity()
        sendAllMessageTypes(client, Topic.fromOrThrow("testIpc", Topic.Type.Name), "Test String")
        client.shutdown(true)
    }
}
