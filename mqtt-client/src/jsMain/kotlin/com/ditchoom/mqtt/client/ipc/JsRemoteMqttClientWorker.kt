package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import kotlinx.coroutines.launch
import org.w3c.dom.MessagePort

class JsRemoteMqttClientWorker(private val delegate: RemoteMqttClientWorker, private val port: MessagePort) {
    fun registerOnMessageObserver() {
        port.onmessage = {
            val obj = it.data?.asDynamic()
            if (obj != null && obj != undefined) {
                val messageType = obj[MESSAGE_TYPE_KEY]
                val msg = readPacketIdMessage(delegate.factory, it)
                val messageId = obj[MESSAGE_INT_KEY]
                if (msg != null) {
                    val (packetId, packet) = msg
                    when (messageType) {
                        MESSAGE_TYPE_CLIENT_PUBLISH -> {
                            delegate.scope.launch {
                                delegate.onPublishQueued(packetId, packet as? IPublishMessage)
                                port.postMessage(
                                    buildSimpleMessage(
                                        MESSAGE_TYPE_CLIENT_PUBLISH_COMPLETION,
                                        messageId as Int
                                    )
                                )
                            }
                        }

                        MESSAGE_TYPE_CLIENT_SUBSCRIBE -> {
                            delegate.scope.launch {
                                delegate.onSubscribeQueued(packetId)
                                port.postMessage(
                                    buildSimpleMessage(
                                        MESSAGE_TYPE_CLIENT_SUBSCRIBE_COMPLETION,
                                        messageId as Int
                                    )
                                )
                            }
                        }

                        MESSAGE_TYPE_CLIENT_UNSUBSCRIBE -> {
                            delegate.scope.launch {
                                delegate.onUnsubscribeQueued(packetId)
                                port.postMessage(
                                    buildSimpleMessage(
                                        MESSAGE_TYPE_CLIENT_UNSUBSCRIBE_COMPLETION,
                                        messageId as Int
                                    )
                                )
                            }
                        }

                        MESSAGE_TYPE_CLIENT_SHUTDOWN -> {
                            delegate.scope.launch { delegate.shutdown(obj[MESSAGE_BOOLEAN_KEY] as Boolean) }
                        }

                        MESSAGE_TYPE_CLIENT_CONNACK_REQUEST -> {
                            delegate.scope.launch {
                                val connack =
                                    delegate.currentConnack()?.serialize(AllocationZone.SharedMemory) as? JsBuffer
                                port.postMessage(
                                    buildPacketIdMessage(MESSAGE_TYPE_CLIENT_CONNACK_RESPONSE, 0, connack)
                                )
                            }
                        }

                        MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_REQUEST -> {
                            delegate.scope.launch {
                                val connack =
                                    delegate.awaitConnectivity().serialize(AllocationZone.SharedMemory) as JsBuffer
                                port.postMessage(
                                    buildPacketIdMessage(MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_RESPONSE, 0, connack)
                                )
                            }
                        }

                        else -> {}
                    }
                }
            }
            Unit
        }
    }
}
