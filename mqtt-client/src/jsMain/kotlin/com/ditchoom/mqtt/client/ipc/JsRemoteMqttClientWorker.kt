package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import kotlinx.coroutines.launch
import org.w3c.dom.MessagePort

class JsRemoteMqttClientWorker(private val delegate: RemoteMqttClientWorker, private val port: MessagePort) {
    fun registerOnMessageObserver() {
        port.onmessage = {
            val obj = it.data?.asDynamic()
            if (obj != null && obj != undefined) {
                val messageType = obj[MESSAGE_TYPE_KEY].toString()
                val msg = readPacketIdMessage(delegate.factory, obj)
                val messageId = (obj[MESSAGE_INT_KEY] ?: obj[MESSAGE_PACKET_ID_KEY]).toString()

                when (messageType) {
                    MESSAGE_TYPE_CLIENT_PUBLISH -> {
                        val (packetId, packet) = checkNotNull(msg)
                        delegate.scope.launch {
                            delegate.onPublishQueued(packetId, packet as? IPublishMessage)
                            val msg2 =
                                buildSimpleMessage(
                                    MESSAGE_TYPE_CLIENT_PUBLISH_COMPLETION,
                                    messageId.toInt(),
                                )
                            port.postMessage(msg2)
                        }
                    }

                    MESSAGE_TYPE_CLIENT_SUBSCRIBE -> {
                        val (packetId, _) = checkNotNull(msg)
                        delegate.scope.launch {
                            delegate.onSubscribeQueued(packetId)
                            try {
                                val postMessage =
                                    buildSimpleMessage(
                                        MESSAGE_TYPE_CLIENT_SUBSCRIBE_COMPLETION,
                                        messageId.toInt(),
                                    )
                                port.postMessage(postMessage)
                            } catch (e: Throwable) {
                                console.error("failed to build and respond with message", e)
                            }
                        }
                    }

                    MESSAGE_TYPE_CLIENT_UNSUBSCRIBE -> {
                        val (packetId, _) = checkNotNull(msg)
                        delegate.scope.launch {
                            delegate.onUnsubscribeQueued(packetId)
                            port.postMessage(
                                buildSimpleMessage(
                                    MESSAGE_TYPE_CLIENT_UNSUBSCRIBE_COMPLETION,
                                    messageId.toInt(),
                                ),
                            )
                        }
                    }

                    MESSAGE_TYPE_CLIENT_SHUTDOWN -> {
                        delegate.scope.launch {
                            delegate.shutdown(obj[MESSAGE_BOOLEAN_KEY] as Boolean)
                            port.postMessage(
                                buildSimpleMessage(
                                    MESSAGE_TYPE_CLIENT_SHUTDOWN_COMPLETION,
                                    messageId.toInt(),
                                ),
                            )
                            port.close()
                        }
                    }

                    MESSAGE_TYPE_CLIENT_CONNACK_REQUEST -> {
                        delegate.scope.launch {
                            val connack =
                                delegate.currentConnack()?.serialize() as? JsBuffer
                            port.postMessage(
                                buildPacketIdMessage(MESSAGE_TYPE_CLIENT_CONNACK_RESPONSE, 0, connack),
                            )
                        }
                    }

                    MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_REQUEST -> {
                        delegate.scope.launch {
                            val connack =
                                delegate.awaitConnectivity().serialize() as JsBuffer
                            port.postMessage(
                                buildPacketIdMessage(MESSAGE_TYPE_CLIENT_AWAIT_CONNECTIVITY_RESPONSE, 0, connack),
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
