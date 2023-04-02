package com.ditchoom.mqtt.client.ipc

import android.os.IBinder
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.connection.MqttBroker
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidRemoteMqttServiceClient(binder: IBinder, service: LocalMqttService) :
    RemoteMqttServiceClient(service) {

    private val aidl = IPCMqttService.Stub.asInterface(binder)
    override val startAllCb: suspend () -> Unit =
        { suspendCoroutine { aidl.startAll(SuspendingMqttCompletionCallback("startAllCb", it)) } }
    override val startCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        suspendCoroutine { aidl.start(brokerId, protocolVersion, SuspendingMqttCompletionCallback("startCb", it)) }
    }
    override val stopAllCb: suspend () -> Unit =
        { suspendCoroutine { aidl.stopAll(SuspendingMqttCompletionCallback("stopAllCb", it)) } }
    override val stopCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        suspendCoroutine { aidl.stop(brokerId, protocolVersion, SuspendingMqttCompletionCallback("stopCb", it)) }
    }

    override suspend fun getClient(broker: MqttBroker): AndroidRemoteMqttClient? {
        val protocolVersion = broker.protocolVersion
        val brokerId = broker.brokerId
        val persistence = service.getPersistence(protocolVersion.toInt())
        persistence.brokerWithId(brokerId) ?: return null // validate broker still exists
        return suspendCoroutine {
            aidl.requestClientOrNull(
                brokerId, protocolVersion,
                object : MqttGetClientCallback.Stub() {
                    override fun onClientReady(client: IPCMqttClient, brokerId: Int, protocolVersion: Byte) {
                        it.resume(AndroidRemoteMqttClient(service.scope, client, broker, persistence))
                    }

                    override fun onClientNotFound() {
                        it.resume(null)
                    }
                }
            )
        }
    }
}
