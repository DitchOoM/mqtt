package com.ditchoom.mqtt.client.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MqttServiceManager {
    private var serviceConnection: MqttServiceConnection? = null
    private var ipcClient: AndroidMqttServiceIPCClient? = null
    suspend fun registerService(context: Context, inMemory: Boolean = false): MqttService {
        val ipcClient = ipcClient
        if (ipcClient != null) {
            return ipcClient
        }
        val i = Intent(context, MqttManagerService::class.java)
        context.startService(i)
        val serviceBinder = suspendCancellableCoroutine {
            val serviceConnection = MqttServiceConnection(context, it)
            this.serviceConnection = serviceConnection
        }
        val c = AndroidMqttServiceIPCClient(serviceBinder, LocalMqttService.buildService(context, inMemory))
        this.ipcClient = c
        return c
    }

    fun unregisterService(context: Context) {
        serviceConnection?.unbind(context)
        serviceConnection = null
    }

    class MqttServiceConnection(
        context: Context,
        private val cont: CancellableContinuation<IBinder>
    ) : ServiceConnection {
        init {
            cont.invokeOnCancellation { unbind(context) }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            cont.resume(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            cont.resumeWithException(MqttException("Failed to connect to service $name", ReasonCode.NOT_AUTHORIZED.byte))
        }

        fun unbind(context: Context) {
            context.unbindService(this)
        }
    }
}
