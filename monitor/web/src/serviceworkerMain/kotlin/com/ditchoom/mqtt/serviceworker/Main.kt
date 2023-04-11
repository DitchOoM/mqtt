package com.ditchoom.mqtt.serviceworker

import com.ditchoom.mqtt.client.ipc.JsRemoteMqttServiceWorker
import com.ditchoom.mqtt.client.ipc.buildMqttServiceIPCServer
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.w3c.dom.AbstractWorker
import org.w3c.dom.DedicatedWorkerGlobalScope
import org.w3c.dom.MessageEvent
import org.w3c.dom.SharedWorker
import org.w3c.dom.SharedWorkerGlobalScope
import org.w3c.dom.Worker
import org.w3c.dom.WorkerGlobalScope
import org.w3c.workers.ExtendableEvent
import org.w3c.workers.ServiceWorkerGlobalScope

external val self: WorkerGlobalScope
val scope = MainScope()
private var ipcServer: JsRemoteMqttServiceWorker? = null


fun main() {
    installWorker()
}

fun installWorker() {
    val workerType = js("Object.prototype.toString.call(self)") as String
    if (workerType.contains("ServiceWorkerGlobalScope")) {
        val self = self as ServiceWorkerGlobalScope
        self.oninstall = {
            val event = it.unsafeCast<ExtendableEvent>()
            event.waitUntil(GlobalScope.promise {
                ipcServer = buildMqttServiceIPCServer(false)
            })
        }
        self.onmessage = {
            ipcServer?.processIncomingMessage(it)
        }

    } else if (workerType.contains("DedicatedWorkerGlobalScope")) {
        val self = self as DedicatedWorkerGlobalScope
        self.onmessage = {
            ipcServer?.processIncomingMessage(it)
        }
        GlobalScope.launch {
            ipcServer = buildMqttServiceIPCServer(false)
        }
    } else if (workerType.contains("SharedWorkerGlobalScope")) {
        (self as SharedWorkerGlobalScope).onconnect = {
            GlobalScope.launch {
                ipcServer = buildMqttServiceIPCServer(false)
                (it as MessageEvent).ports[0].onmessage = {
                    ipcServer?.processIncomingMessage(it)
                }
            }
            Unit
        }

    }
}

enum class WorkerType {
    DedicatedWorker,
    SharedWorker,
    ServiceWorker
}

suspend fun findOrRegisterServiceWorker(type: WorkerType, scriptUrl: String = "serviceworker.js"): AbstractWorker {
    when (type) {
        WorkerType.DedicatedWorker -> {
            val w = Worker(scriptUrl)
            w.onerror = {
                console.error("worker error", it)
            }
            w.onmessage = {
                console.log("onmsg worker", it)
            }
            delay(500)
            return w
        }

        WorkerType.ServiceWorker -> {
            val registeredServiceWorker = window.navigator
                .serviceWorker.getRegistrations().await()
                .mapNotNull { it.active }
                .firstOrNull { it.scriptURL.endsWith(scriptUrl) }
            if (registeredServiceWorker != null) {
                return registeredServiceWorker
            }
            window.navigator.serviceWorker.register(scriptUrl)
            val serviceWorkerRegistration = window.navigator.serviceWorker.ready.await().active!!
            serviceWorkerRegistration.onerror = {
                console.error("serviceworker error", it)
            }
            return serviceWorkerRegistration
        }

        WorkerType.SharedWorker -> {
            val w = SharedWorker(scriptUrl)
            w.onerror = {
                console.error("shared worker error", it)
            }
            w.port.onmessage = {
                console.log("onmsg sharedworker", it)
            }
            delay(1000)
            return w
        }
    }
}