package com.ditchoom.mqtt.client

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Factory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LocalMqttService private constructor(
    internal val scope: CoroutineScope,
    private val persistenceV4: Persistence,
    private val persistenceV5: Persistence,
    private val connectionFactory: (MqttBroker) -> suspend () -> Connection<ControlPacket>,
) : MqttService {
    private val brokerClientMap = mutableMapOf<Byte, HashMap<Int, LocalMqttClient>>()

    fun getPersistence(broker: MqttBroker): Persistence = getPersistence(broker.connectionRequest)

    fun getPersistence(connectionRequest: IConnectionRequest): Persistence = getPersistence(connectionRequest.protocolVersion)

    fun getPersistence(protocolVersion: Byte) = getPersistence(protocolVersion.toInt())

    fun getPersistence(protocolVersion: Int): Persistence =
        if (protocolVersion == 5) {
            persistenceV5
        } else {
            persistenceV4
        }

    override suspend fun start(broker: MqttBroker) {
        val client = brokerClientMap[broker.protocolVersion]?.get(broker.identifier)

        if (client == null) {
            val c = LocalMqttClient.start(scope, broker, getPersistence(broker), connectionFactory(broker))
            brokerClientMap
                .getOrPut(broker.protocolVersion) { HashMap() }
                .getOrPut(broker.identifier) { c }
        } else if (client.isStopped()) {
            val c = LocalMqttClient.start(scope, broker, getPersistence(broker), connectionFactory(broker))
            brokerClientMap
                .getOrPut(broker.protocolVersion) { HashMap() }[broker.identifier] = c
        }
    }

    override suspend fun start() {
        val allBrokers = allBrokers().associateBy { Pair(it.protocolVersion, it.brokerId) }
        val currentBrokers =
            brokerClientMap.values
                .map { it.values }
                .flatten()
                .associateBy { Pair(it.broker.protocolVersion, it.broker.brokerId) }

        val newBrokers = allBrokers.keys - currentBrokers.keys
        newBrokers.forEach { pair ->
            val (protocolVersion, brokerId) = pair
            val broker = allBrokers[pair]!!
            val c = LocalMqttClient.start(scope, broker, getPersistence(broker), connectionFactory(broker))
            brokerClientMap.getOrPut(protocolVersion) { HashMap() }[brokerId] = c
        }
    }

    override suspend fun stop() {
        brokerClientMap.values.map { it.values }.flatten().forEach {
            it.shutdown()
        }
        brokerClientMap.clear()
    }

    override suspend fun stop(broker: MqttBroker) {
        val c = brokerClientMap[broker.protocolVersion]?.remove(broker.identifier) ?: return
        c.shutdown()
    }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttBroker =
        getPersistence(connectionRequest)
            .addBroker(connectionOps, connectionRequest)

    override suspend fun allBrokers(): Collection<MqttBroker> = persistenceV4.allBrokers() + persistenceV5.allBrokers()

    override suspend fun getClient(broker: MqttBroker): LocalMqttClient? = brokerClientMap[broker.protocolVersion]?.get(broker.identifier)

    override suspend fun removeBroker(
        brokerId: Int,
        protocolVersion: Byte,
    ) {
        getPersistence(protocolVersion.toInt()).removeBroker(brokerId)
    }

    suspend fun shutdownAndCleanup() {
        brokerClientMap.values.map { it.values }.flatten().forEach {
            it.shutdown()
        }
        brokerClientMap.clear()
        // Do NOT cancel the scope. This service is cached as an AppInitializer singleton
        // and must outlive any single MqttManagerService incarnation. Cancelling here
        // makes the next service onCreate retrieve a dead instance whose
        // LocalMqttClient.start(scope, ...) launches into a cancelled scope — cm.run()
        // never executes, no CONNACK arrives, awaitConnectivity() hangs forever. Pinned
        // by androidInstrumentedTest WsConnectIsolationTest.serviceSurvivesShutdownAndCleanupCycle.
    }

    companion object {
        fun getControlPacketFactory(protocolVersion: Int): ControlPacketFactory =
            if (protocolVersion == 5) {
                ControlPacketV5Factory
            } else {
                ControlPacketV4Factory
            }

        suspend fun buildService(
            connectionFactory: (MqttBroker) -> suspend () -> Connection<ControlPacket>,
            androidContext: Any? = null,
            inMemory: Boolean = false,
        ): LocalMqttService =
            suspendCoroutine { cont ->
                buildService(connectionFactory, androidContext, inMemory) { cont.resume(it) }
            }

        fun buildService(
            connectionFactory: (MqttBroker) -> suspend () -> Connection<ControlPacket>,
            androidContext: Any? = null,
            inMemory: Boolean = false,
            cb: (LocalMqttService) -> Unit,
        ) {
            val scope = CoroutineScope(Dispatchers.Default + CoroutineName("Mqtt Service"))
            scope.launch {
                val persistenceV4 =
                    try {
                        ConnectionRequest("")
                            .controlPacketFactory
                            .defaultPersistence(androidContext, inMemory = inMemory)
                    } catch (e: Exception) {
                        println("\r\nFailed to allocate default persistence, using InMemory")
                        InMemoryPersistence()
                    }
                val persistenceV5 =
                    try {
                        com.ditchoom.mqtt5.controlpacket
                            .ConnectionRequest("")
                            .controlPacketFactory
                            .defaultPersistence(androidContext, inMemory = inMemory)
                    } catch (e: Exception) {
                        println("\r\nFailed to allocate default persistence, using InMemory")
                        InMemoryPersistence()
                    }
                val service = LocalMqttService(scope, persistenceV4, persistenceV5, connectionFactory)
                cb(service)
            }
        }
    }
}
