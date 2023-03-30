package com.ditchoom.mqtt.client.ipc

import android.os.IBinder
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttClientAidl
import com.ditchoom.mqtt.client.MqttIpcClientCallback
import com.ditchoom.mqtt.client.MqttServiceAidl
import com.ditchoom.mqtt.connection.MqttBroker
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidMqttServiceIPCClient(binder: IBinder, service: LocalMqttService) :
    MqttServiceIPCClient(service) {

    private val aidl = MqttServiceAidl.Stub.asInterface(binder)
    override val startAllCb: suspend () -> Unit = { suspendCoroutine { aidl.startAll(SuspendingCoroutine("startAllCb", it)) } }
    override val startCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        suspendCoroutine { aidl.start(brokerId, protocolVersion, SuspendingCoroutine("startCb", it)) }
    }
    override val stopAllCb: suspend () -> Unit = { suspendCoroutine { aidl.stopAll(SuspendingCoroutine("stopAllCb", it)) } }
    override val stopCb: suspend (Int, Byte) -> Unit = { brokerId, protocolVersion ->
        suspendCoroutine { aidl.stop(brokerId, protocolVersion, SuspendingCoroutine("stopCb", it)) }
    }

    override suspend fun getClient(broker: MqttBroker): AndroidMqttClientIPCClient? {
        val protocolVersion = broker.protocolVersion
        val brokerId = broker.brokerId
        val persistence = service.getPersistence(protocolVersion.toInt())
        persistence.brokerWithId(brokerId) ?: return null // validate broker still exists
        return suspendCoroutine {
            aidl.requestClientOrNull(
                brokerId, protocolVersion,
                object : MqttIpcClientCallback.Stub() {
                    override fun onClientReady(client: MqttClientAidl, brokerId: Int, protocolVersion: Byte) {
                        it.resume(AndroidMqttClientIPCClient(service.scope, client, broker, persistence))
                    }

                    override fun onClientNotFound() {
                        it.resume(null)
                    }
                }
            )
        }
    }
}
