package com.ditchoom.mqtt.client.ipc

import android.os.RemoteException
import com.ditchoom.mqtt.client.OnMqttCompletionCallback
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SuspendingCoroutine(private val name: String, private val cont: Continuation<Unit>) : OnMqttCompletionCallback.Stub() {
    override fun onSuccess() {
        cont.resume(Unit)
    }

    override fun onError(messageOrNull: String?) {
        cont.resumeWithException(RemoteException("Failed to run remote Mqtt Command $name $messageOrNull"))
    }
}
