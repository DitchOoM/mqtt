package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MqttService private constructor(
    private val scope: CoroutineScope,
    private val persistenceV4: Persistence,
    private val persistenceV5: Persistence,
) {
    private val brokerClientMap = mutableMapOf<Int, MqttClient>()
    private var observer: Observer? = null

    var incomingMessages: (MqttBroker, ControlPacket) -> Unit = { _, _ -> }
    var sentMessages: (MqttBroker, Collection<ControlPacket>) -> Unit = { _, _ -> }

    fun assignObservers(observer: Observer?) {
        this.observer = observer
    }

    fun getPersistence(broker: MqttBroker): Persistence =
        getPersistence(broker.connectionRequest)

    fun getPersistence(connectionRequest: IConnectionRequest): Persistence =
        getPersistence(connectionRequest.protocolVersion)

    fun getPersistence(protocolVersion: Int): Persistence {
        return if (protocolVersion == 5) {
            println("get persistence v5 $protocolVersion")
            persistenceV5
        } else {
            println("get persistence v4 $protocolVersion")
            persistenceV4
        }
    }

    fun start(cb: (() -> Unit)? = null) = scope.launch {
        val allBrokers = allMqttBrokers()
        val newBrokers = allBrokers.map { it.identifier } - brokerClientMap.keys
        brokerClientMap += newBrokers.associateWith { newBrokerId ->
            val broker = allBrokers.first { newBrokerId == it.identifier }
            val c = MqttClient.stayConnected(scope, broker, getPersistence(broker), observer)
            c.incomingMessage = {
                incomingMessages(broker, it)
            }
            c.sentMessage = {
                sentMessages(broker, it)
            }
            c
        }
        cb?.invoke()
    }

    fun stop() = scope.launch {
        brokerClientMap.values.forEach {
            it.shutdown()
        }
        brokerClientMap.clear()
    }

    fun stayConnected(broker: MqttBroker): MqttClient {
        val oldClient = brokerClientMap.remove(broker.identifier)
        if (oldClient != null) {
            return oldClient
        }
        val client = MqttClient.stayConnected(scope, broker, getPersistence(broker), observer)
        client.incomingMessage = {
            incomingMessages(broker, it)
        }
        client.sentMessage = {
            sentMessages(broker, it)
        }
        brokerClientMap[broker.identifier] = client
        return client
    }

    fun stop(broker: MqttBroker) = scope.launch {
        brokerClientMap.remove(broker.identifier)?.shutdown()
    }

    fun addMqttBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
        cb: (MqttBroker) -> Unit
    ) = scope.launch {
        val broker = addMqttBroker(connectionOps, connectionRequest)
        scope.launch(Dispatchers.Main) {
            cb(broker)
        }
    }

    suspend fun addMqttBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker = getPersistence(connectionRequest)
        .addBroker(connectionOps, connectionRequest)

    fun allMqttBrokers(cb: (Collection<MqttBroker>) -> Unit) = scope.launch {
        val brokers = allMqttBrokers()
        launch(Dispatchers.Main) {
            cb(brokers)
        }
    }

    suspend fun allMqttBrokers(): Collection<MqttBroker> =
        persistenceV4.allBrokers() + persistenceV5.allBrokers()

    fun getClient(broker: MqttBroker): MqttClient? = brokerClientMap[broker.identifier]

    fun removeBroker(broker: MqttBroker) = scope.launch { getPersistence(broker).removeBroker(broker.identifier) }

    companion object {
        suspend fun buildService(androidContext: Any? = null): MqttService = suspendCoroutine { cont ->
            buildService(androidContext) { cont.resume(it) }
        }

        fun buildService(androidContext: Any? = null, cb: (MqttService) -> Unit) {
            val scope = CoroutineScope(Dispatchers.Default + CoroutineName("Mqtt Service"))
            scope.launch {
                val persistenceV4 = try {
                    val p = ConnectionRequest("")
                        .controlPacketFactory.defaultPersistence(androidContext)
                    println("Alloc $p")
                    p
                } catch (e: Exception) {
                    println("Failed to allocate default persistence, using InMemory")
                    InMemoryPersistence()
                }
                val persistenceV5 = try {
                    val p = com.ditchoom.mqtt5.controlpacket.ConnectionRequest("")
                        .controlPacketFactory.defaultPersistence(androidContext)
                    println("Alloc $p")
                    p
                } catch (e: Exception) {
                    println("Failed to allocate default persistence, using InMemory")
                    InMemoryPersistence()
                }
                val service = MqttService(scope, persistenceV4, persistenceV5)
                launch(Dispatchers.Main) {
                    cb(service)
                }
            }
        }
    }
}
