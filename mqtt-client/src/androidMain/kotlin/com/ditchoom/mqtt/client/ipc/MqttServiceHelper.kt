package com.ditchoom.mqtt.client.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MqttServiceHelper {
    private var serviceConnection: MqttServiceConnection? = null
    private var ipcClient: AndroidRemoteMqttServiceClient? = null

    suspend fun registerService(
        context: Context,
        inMemory: Boolean = false,
    ): MqttService {
        val existing = ipcClient
        if (existing != null) {
            return existing
        }
        val i = Intent(context, MqttManagerService::class.java)
        context.startService(i)
        val serviceBinder =
            suspendCancellableCoroutine { cont ->
                val conn = MqttServiceConnection(cont)
                cont.invokeOnCancellation {
                    serviceConnection = null
                    ipcClient = null
                    runCatching { context.unbindService(conn) }
                }
                if (!context.bindService(i, conn, Context.BIND_AUTO_CREATE)) {
                    cont.resumeWithException(RemoteException("Failed to allocate bind mqtt service"))
                }
                serviceConnection = conn
            }
        val clientSideService =
            LocalMqttService.buildService(
                connectionFactory = { _ ->
                    { _ -> throw UnsupportedOperationException("Client proxy does not create connections directly; use AIDL") }
                },
                androidContext = context,
                inMemory = inMemory,
            )
        val c = AndroidRemoteMqttServiceClient(serviceBinder, clientSideService)
        ipcClient = c
        return c
    }

    fun unregisterService(context: Context) {
        val conn = serviceConnection ?: return
        serviceConnection = null
        ipcClient = null
        runCatching { context.unbindService(conn) }
    }

    class MqttServiceConnection(
        private val cont: CancellableContinuation<IBinder>,
    ) : ServiceConnection {
        @Volatile
        var bound: Boolean = false
            private set

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            bound = true
            if (cont.isActive) {
                cont.resume(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            if (cont.isActive) {
                cont.resumeWithException(
                    MqttException(
                        "Failed to connect to service $name",
                        ReasonCode.NOT_AUTHORIZED.byte,
                    ),
                )
            }
        }
    }
}
