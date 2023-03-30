package com.ditchoom.mqtt.serviceworker

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.w3c.dom.events.Event
import org.w3c.workers.ExtendableEvent
import org.w3c.workers.ServiceWorkerGlobalScope
import web.navigator.navigator
import web.serviceworker.ServiceWorker

//var ipcServer: JsMqttServiceIPCServer? = null
external val self: ServiceWorkerGlobalScope
val scope = MainScope()

fun main() {
    installServiceWorker()
}

fun installServiceWorker() {
    self.oninstall = { event ->
        console.log("\r\nSW on install")
        waitUntil(event) {

//            ipcServer = buildMqttServiceIPCServer()
        }
    }
    self.onmessage = {
//        ipcServer?.processIncomingMessage(it)
    }
}

fun waitUntil(event: Event, cb: suspend (() -> Unit)) {
    val extendableEvent = event.unsafeCast<ExtendableEvent>()
    extendableEvent.waitUntil(scope.promise { cb() })
}

suspend fun findOrRegisterServiceWorker(scriptUrl: String = "serviceworker.js"): ServiceWorker {
    val registeredServiceWorker = navigator
        .serviceWorker.getRegistrations().await()
        .mapNotNull { it.active }
        .firstOrNull { it.scriptURL.endsWith(scriptUrl) }
    if (registeredServiceWorker != null) {
        return registeredServiceWorker
    }
    console.log("\r\nregister service worker ", navigator.serviceWorker)
    navigator.serviceWorker.register(scriptUrl)
    val serviceWorkerRegistration = navigator.serviceWorker.ready.await()
    return checkNotNull(serviceWorkerRegistration.active) { "SW should be active after the ready state." }
}