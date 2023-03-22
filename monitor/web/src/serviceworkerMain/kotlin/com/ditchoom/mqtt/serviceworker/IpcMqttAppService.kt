package com.ditchoom.mqtt.serviceworker

import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import web.serviceworker.ServiceWorker
import kotlin.js.Promise

class IpcMqttAppService(
    private val service: MqttService,
    private val serviceWorker: ServiceWorker,
) {
    fun addMqttBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): Promise<MqttBroker> = GlobalScope.promise {
        val broker = service.addMqttBroker(connectionOps, connectionRequest)
        console.log("app service ${service.allMqttBrokers().joinToString()}")
        serviceWorker.postMessage(
            MqttServiceWorkerMessage.BrokerAdded(
                broker.identifier,
                connectionRequest.protocolVersion
            )
        )
        broker
    }

    fun allMqttBrokers(): Promise<Collection<MqttBroker>> = GlobalScope.promise { service.allMqttBrokers() }
    fun removeAllBrokersAndStop() {
        serviceWorker.postMessage(MqttServiceWorkerMessage.RemoveAllBrokersAndStop())
    }

    fun startClient(broker: MqttBroker): IpcMqttAppClient {
        val persistence = service.getPersistence(broker)
        val client = IpcMqttAppClient(
            broker,
            serviceWorker,
            broker.connectionRequest.controlPacketFactory,
            persistence
        )
        GlobalScope.launch {
            client.messageFlow.first()
        }
        return client
    }

    companion object {
        private var ipcService: IpcMqttAppService? = null
        private var serviceWorker: ServiceWorker? = null

        fun buildService(): Promise<Pair<IpcMqttAppService, ServiceWorker>> {
            val ipc = ipcService
            val serviceWorkerLocal = serviceWorker
            return if (ipc != null && serviceWorkerLocal != null) {
                Promise { resolve, _ ->
                    resolve(Pair(ipc, serviceWorkerLocal))
                }
            } else {
                GlobalScope.promise {
                    val sw = findOrRegisterServiceWorker()
                    serviceWorker = sw
                    val service = MqttService.buildService()
                    val s = IpcMqttAppService(service, sw)
                    ipcService = s
                    Pair(s, sw)
                }
            }
        }
    }
}