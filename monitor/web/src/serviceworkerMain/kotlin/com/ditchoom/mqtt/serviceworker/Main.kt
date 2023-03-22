package com.ditchoom.mqtt.serviceworker

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.serviceworker.IpcMqttAppClient.Companion.broadcastChannelName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.w3c.dom.BroadcastChannel
import org.w3c.dom.events.Event
import org.w3c.workers.ExtendableEvent
import org.w3c.workers.ServiceWorkerGlobalScope
import web.navigator.navigator
import web.serviceworker.ServiceWorker

external val self: ServiceWorkerGlobalScope
val scope = MainScope()
private var mqttService: MqttService? = null
val broadcastChannelMap = HashMap<Pair<Int, Byte>, BroadcastChannel>()
fun main() {
    println("hello from sw")
    installServiceWorker()
}

fun installServiceWorker() {
    self.oninstall = { event ->
        console.log("SW on install")
        waitUntil(event) {
            val service = MqttService.buildService(null)
            val ipcMqttService = IpcMqttService(service, broadcastChannelMap)
            service.incomingMessages = { mqttBroker, controlPacket ->
                ipcMqttService.controlPacketReceived(
                    mqttBroker.identifier,
                    mqttBroker.connectionRequest.protocolVersion,
                    controlPacket.controlPacketValue,
                    controlPacket.packetIdentifier,
                    (controlPacket.serialize() as JsBuffer).buffer
                )
            }
            service.sentMessages = { mqttBroker, controlPackets ->
                controlPackets.forEach { controlPacket ->
                    ipcMqttService.controlPacketSent(
                        mqttBroker.identifier,
                        mqttBroker.connectionRequest.protocolVersion,
                        controlPacket.controlPacketValue,
                        controlPacket.packetIdentifier,
                        (controlPacket.serialize() as JsBuffer).buffer
                    )
                }
            }
            self.onmessage = {
                console.log("sw incoming message", it)
                ipcMqttService.onIncomingMessageEvent(it)
            }
            service.assignObservers(IpcLoggingObserverServiceWorker { brokerId, protocolVersion ->
                broadcastChannelMap.getOrPut(Pair(brokerId, protocolVersion)) {
                    BroadcastChannel(broadcastChannelName(brokerId, protocolVersion))
                }
            })
            mqttService = service
        }
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

    self
    if (registeredServiceWorker != null) {
        return registeredServiceWorker
    }
    console.log("register service worker ", navigator.serviceWorker)
    navigator.serviceWorker.register(scriptUrl)
    val serviceWorkerRegistration = navigator.serviceWorker.ready.await()
    return checkNotNull(serviceWorkerRegistration.active) { "SW should be active after the ready state." }
}