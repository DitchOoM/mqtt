import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.serviceworker.WorkerType
import com.ditchoom.mqtt.serviceworker.findOrRegisterServiceWorker
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.dom.create
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.js.div
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import web.html.HTMLInputElement
import web.html.HTMLSelectElement
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds

private lateinit var service: MqttService
fun main() {
    GlobalScope.launch {
        val serviceWorker = findOrRegisterServiceWorker(WorkerType.DedicatedWorker)
        service = MqttService.buildNewService(true, serviceWorker)
        mainSuspend()
    }
}

private suspend fun mainSuspend() {
    val brokers = service.allBrokers()

    val root: Element = document.getElementById("root")!!
    if (brokers.isEmpty()) {
        root.appendChild(connectionOpNode(service))
        root.appendChild(connectionRequest4Node())
        root.appendChild(doneButton(service))
    } else {
        brokers.forEach { broker ->
            val child = document.create.button {
                p { +broker.toString() }
                onClickFunction = {
                    GlobalScope.launch {
                        service.start(broker)
                        val client = checkNotNull(service.getClient(broker))
                        loadLogsForClient(client)
                    }
                }
            }
            root.appendChild(child)
        }
        var deleteAllRef: Node? = null
        val deleteAll = root.appendChild(document.create.button(name = "deleteAll") {
            id = "deleteAllBrokers"
            p { +"Delete All Brokers" }
            onClickFunction = {
                root.childNodes.asList().forEach { root.removeChild(it) }
                deleteAllRef?.let { it1 -> root.removeChild(it1) }
                GlobalScope.launch {
                    val allBrokers = service.allBrokers()
                    service.stop()
                    allBrokers.forEach { service.removeBroker(it.brokerId, it.protocolVersion) }
                    mainSuspend()
                }
            }
        })
        deleteAllRef = deleteAll
    }
}

fun doneButton(service: MqttService) = document.create.button {
    id = "doneButton"
    p {
        +" Connect"
    }
    onClickFunction = { event ->
        val mqttVersionSelect = document.getElementById("mqttVersion") as HTMLSelectElement
        val host = (document.getElementById("host") as HTMLInputElement).value
        val port = (document.getElementById("port") as HTMLInputElement).value.toInt()
        val tls = (document.getElementById("tls") as HTMLInputElement).checked
        val connectionTimeoutSeconds = (document.getElementById("connectionTimeout") as HTMLInputElement).value.toInt()
        val protocol = (document.getElementById("protocol") as HTMLInputElement).value
        val endpoint = (document.getElementById("endpoint") as HTMLInputElement).value

        val options = MqttConnectionOptions.WebSocketConnectionOptions(
            host,
            port,
            tls,
            connectionTimeoutSeconds.seconds,
            protocols = protocol.split(", "),
            websocketEndpoint = endpoint
        )
        val clientId = (document.getElementById("clientID") as HTMLInputElement).value

        val keepAlive = (document.getElementById("keepAlive") as HTMLInputElement).value.toIntOrNull() ?: -1
        val hasUsername = (document.getElementById("hasUsername") as HTMLInputElement).checked
        val username = if (hasUsername) {
            (document.getElementById("username") as HTMLInputElement).value
        } else {
            null
        }
        val hasPassword = (document.getElementById("hasPassword") as HTMLInputElement).checked
        val password = if (hasPassword) {
            (document.getElementById("password") as HTMLInputElement).value
        } else {
            null
        }
        val includeWill = (document.getElementById("includeWill") as HTMLInputElement).checked
        val willTopic = if (includeWill) (document.getElementById("willTopic") as HTMLInputElement).value else null
        val willPayload = if (includeWill) {
            (document.getElementById("willPayload") as HTMLInputElement).value.toReadBuffer(Charset.UTF8) as? PlatformBuffer
        } else {
            null
        }

        val willRetain = (document.getElementById("willRetain") as HTMLInputElement).checked

        val willQos = getQos("willQos")
        val cleanStart = (document.getElementById("cleanStart") as HTMLInputElement).checked

        val request = if (mqttVersionSelect.value == "5") {
            val hasMqttExtras = (document.getElementById("mqtt5Extras") as HTMLInputElement).checked
            val sessionExpiryIntervalSeconds = if (hasMqttExtras) {
                (document.getElementById("sessionExpiryIntervalSeconds") as HTMLInputElement).value.toULongOrNull()
            } else {
                null
            }
            val receiveMaximum = if (hasMqttExtras) {
                (document.getElementById("receiveMaximum") as HTMLInputElement).value.toIntOrNull()
            } else {
                null
            }
            val maxPacketSize = if (hasMqttExtras) {
                (document.getElementById("maxPacketSize") as HTMLInputElement).value.toULongOrNull()
            } else {
                null
            }
            val topicAliasMax = if (hasMqttExtras) {
                (document.getElementById("topicAliasMax") as HTMLInputElement).value.toIntOrNull()
            } else {
                null
            }

            val requestResponseInformation = if (hasMqttExtras) {
                (document.getElementById("requestResponseInformation") as HTMLInputElement).checked
            } else {
                false
            }
            val requestProblemInformation = if (hasMqttExtras) {
                (document.getElementById("requestProblemInformation") as HTMLInputElement).checked
            } else {
                false
            }
            ConnectionRequest(
                clientId, keepAlive, cleanStart, username, password, willTopic, willPayload, willRetain, willQos,
                props = ConnectionRequest.VariableHeader.Properties(
                    sessionExpiryIntervalSeconds,
                    receiveMaximum,
                    maxPacketSize,
                    topicAliasMax,
                    requestResponseInformation,
                    requestProblemInformation
                )
            )
        } else {
            com.ditchoom.mqtt3.controlpacket.ConnectionRequest(
                clientId, keepAlive, cleanStart, username, password, willTopic, willPayload, willRetain, willQos
            )
        }
        GlobalScope.launch {
            val client = service.addBrokerAndStartClient(listOf(options), request)
            loadLogsForClient(client)
        }
    }
}

fun getQos(id: String): QualityOfService {
    return when ((document.getElementById(id) as HTMLSelectElement).value.toIntOrNull()) {
        1 -> QualityOfService.AT_LEAST_ONCE
        2 -> QualityOfService.EXACTLY_ONCE
        else -> QualityOfService.AT_MOST_ONCE
    }
}

fun loadLogsForClient(client: MqttClient) {
    val root: Element = document.getElementById("root")!!
    val doneButton = document.getElementById("doneButton")
    val connection4Options = document.getElementById("connectionRequest4")
    val connection5Options = document.getElementById("connectionRequest5")
    val deleteAllButton = document.getElementById("deleteAllBrokers")
    if (doneButton != null) {
        root.removeChild(doneButton)
    }
    if (connection4Options != null) {
        root.removeChild(connection4Options)
    }
    if (connection5Options != null) {
        root.removeChild(connection5Options)
    }
    if (deleteAllButton != null) {
        root.removeChild(deleteAllButton)
    }

    root.childNodes.asList().forEach { node -> root.removeChild(node) }
    root.append(subscribeUi(client))
    val pubUi = publishUi(client)
    root.append(pubUi)
    root.append(unsubscribeUi(client))
    var disconnectButton: HTMLElement? = null
    val b = document.create.button {
        p { +"Disconnect" }
        id = "disconnectButton"
        onClickFunction = {
            root.childNodes.asList().forEach { node ->
                root.removeChild(node)
            }
            root.removeChild(pubUi)
            disconnectButton?.let { it1 -> root.removeChild(it1) }
            GlobalScope.launch {
                client.shutdown()
                mainSuspend()
            }
        }
    }
    disconnectButton = b
    root.append(b)
}

fun subscribeUi(client: MqttClient) = document.create.div {
    p { +"Subscribe Topic" }
    input(InputType.text) {
        id = "subTopic"
        placeholder = "topic/test/#"
    }
    p { +"Subscribe Max QoS" }
    select {
        id = "subQos"
        option {
            label = "At Most Once (0)"
            value = "0"
        }
        option {
            label = "At Least Once (1)"
            value = "1"
        }
        option {
            label = "Exactly Once (2)"
            value = "2"
        }
    }
    button {
        p { +"Subscribe" }
        onClickFunction = {
            val topicFilter = document.getElementById("subTopic") as HTMLInputElement
            val topic = Topic.fromOrNull(topicFilter.value, Topic.Type.Filter)
            val qos = getQos("subQos")

            if (topic != null) {
                val factory = client.packetFactory
                val sub = factory.subscribe(
                    topicFilter = topic,
                    maximumQos = qos,
                )
                GlobalScope.launch {
                    client.subscribe(sub)
                }
            }
        }
    }
}

fun publishUi(client: MqttClient) = document.create.div {
    p { +"Publish Topic" }
    input(InputType.text) {
        id = "publishTopic"
        placeholder = "topic/test"
    }
    p { +"Publish QoS" }
    select {
        id = "pubQos"
        option {
            label = "At Most Once (0)"
            value = "0"
        }
        option {
            label = "At Least Once (1)"
            value = "1"
        }
        option {
            label = "Exactly Once (2)"
            value = "2"
        }
    }
    p { +"Publish Payload" }
    input(InputType.text) {
        id = "publishPayload"
        placeholder = "payload string"
    }
    button {
        p { +"Publish" }
        onClickFunction = {
            val topicFilter = document.getElementById("publishTopic") as HTMLInputElement
            val topic = Topic.fromOrNull(topicFilter.value, Topic.Type.Name)
            val qos = getQos("pubQos")
            val payload = (document.getElementById("publishPayload") as HTMLInputElement)
                .value.toReadBuffer(Charset.UTF8)

            if (topic != null) {
                val factory = client.packetFactory
                val pub = factory.publish(
                    topicName = topic,
                    qos = qos,
                    payload = payload
                )
                GlobalScope.launch {
                    client.publish(pub)
                }
            }
        }
    }
}


fun unsubscribeUi(client: MqttClient) = document.create.div {
    p { +"Unsubscribe Topic" }
    input(InputType.text) {
        id = "unsubTopic"
        placeholder = "topic/test/#"
    }
    button {
        p { +"Unsubscribe" }
        onClickFunction = {
            val topicFilter = document.getElementById("unsubTopic") as HTMLInputElement
            val topic = Topic.fromOrNull(topicFilter.value, Topic.Type.Filter)
            if (topic != null) {
                val factory = client.packetFactory
                val unsub = factory.unsubscribe(topic)
                GlobalScope.launch {
                    client.unsubscribe(unsub)
                }
            }
        }
    }
}

fun connectionOpNode(mqttService: MqttService) = document.create.div {
    id = "connectionOp"
    p {
        +"Mqtt Version "
        select {
            id = "mqttVersion"
            option {
                label = "4"
                value = "4"
            }
            option {
                label = "5"
                value = "5"
            }
            onChangeFunction = {
                val mqttVersion = (document.getElementById("mqttVersion") as HTMLSelectElement).value
                val root: Element = document.getElementById("root")!!
                val connection4Options = document.getElementById("connectionRequest4")
                val connection5Options = document.getElementById("connectionRequest5")
                val doneButtonElement = document.getElementById("doneButton")
                if (doneButtonElement != null) {
                    root.removeChild(doneButtonElement)
                }
                if (mqttVersion == "5") {
                    if (connection4Options != null) {
                        root.removeChild(connection4Options)
                    }
                    root.appendChild(connectionRequest5Node())
                } else {
                    if (connection5Options != null) {
                        root.removeChild(connection5Options)
                    }
                    root.appendChild(connectionRequest4Node())
                }
                root.appendChild(doneButton(mqttService))
            }
        }
    }

    p {
        +"Host "
        input(InputType.text) {
            id = "host"
            value = "localhost"
            placeholder = "host"
        }
    }
    p {
        +"Port "
        input(InputType.number) {
            id = "port"
            value = "80"
        }
    }
    p {
        +"TLS "
        input(InputType.checkBox) {
            id = "tls"
            checked = false
        }
    }

    p {
        +"Connection Timeout Seconds "
        input(InputType.number) {
            id = "connectionTimeout"
            value = "15"
        }
    }
    p {
        +"Protocol "
        input(InputType.text) {
            id = "protocol"
            value = "mqttv3.1"
        }
    }

    p {
        +"Endpoint "
        input(InputType.text) {
            id = "endpoint"
            value = "/mqtt"
        }
    }
}


fun connectionRequest4Node() = document.create.div {
    id = "connectionRequest4"
    p {
        +"Client ID "
        input(InputType.text) {
            id = "clientID"
            value = "meow${Random.nextUInt()}-Web"
        }
    }
    p {
        +"Keep Alive Seconds "
        input(InputType.number) {
            id = "keepAlive"
            value = "15"
        }
    }
    p {
        +"Has Username "
        input(InputType.checkBox) {
            id = "hasUsername"
            checked = false
            onChangeFunction = {
                val hasUsername = document.getElementById("hasUsername")!!
                val checked = (hasUsername as HTMLInputElement).checked
                val username = document.getElementById("username") as HTMLInputElement
                username.disabled = !checked
                if (username.disabled) {
                    username.value = ""
                }
            }
        }
    }
    p {
        +"Username "
        input(InputType.text) {
            id = "username"
            value = ""
            disabled = true
        }
    }
    p {
        +"Has Password "
        input(InputType.checkBox) {
            id = "hasPassword"
            checked = false
            onChangeFunction = {
                val hasPassword = document.getElementById("hasPassword")!!
                val checked = (hasPassword as HTMLInputElement).checked
                val password = document.getElementById("password") as HTMLInputElement
                password.disabled = !checked
                if (password.disabled) {
                    password.value = ""
                }
            }
        }
    }
    p {
        +"Password "
        input(InputType.password) {
            id = "password"
            value = ""
            disabled = true
        }
    }
    p {
        +"Include Will "
        input(InputType.checkBox) {
            id = "includeWill"
            checked = false
            onChangeFunction = {
                val includeWill = document.getElementById("includeWill") as HTMLInputElement
                val willTopic = document.getElementById("willTopic") as HTMLInputElement
                val willPayload = document.getElementById("willPayload") as HTMLInputElement
                val willRetain = document.getElementById("willRetain") as HTMLInputElement
                val willQos = document.getElementById("willQos") as HTMLSelectElement
                willTopic.disabled = !includeWill.checked
                if (willTopic.disabled) {
                    willTopic.value = ""
                }
                willPayload.disabled = !includeWill.checked
                if (willPayload.disabled) {
                    willPayload.value = ""
                }
                willRetain.disabled = !includeWill.checked
                if (willRetain.disabled) {
                    willRetain.checked = false
                }
                willQos.disabled = !includeWill.checked
                if (willQos.disabled) {
                    willQos.value = "0"
                }
            }
        }
    }
    p {
        +"Will Topic "
        input(InputType.text) {
            id = "willTopic"
            value = ""
            disabled = true
        }
    }
    p {
        +"Will Payload "
        input(InputType.text) {
            id = "willPayload"
            value = ""
            disabled = true
        }
    }
    p {
        +"Will Retain "
        input(InputType.checkBox) {
            id = "willRetain"
            checked = false
            disabled = true
        }
    }
    p {
        +"Will QoS "
        select {
            id = "willQos"
            disabled = true
            option {
                label = "At Most Once (0)"
                value = "0"
            }
            option {
                label = "At Least Once (1)"
                value = "1"
            }
            option {
                label = "Exactly Once (2)"
                value = "2"
            }
        }
    }
    p {
        +"Clean Session "
        input(InputType.checkBox) {
            id = "cleanStart"
            checked = false
        }
    }
}


fun connectionRequest5Node() = document.create.div {
    id = "connectionRequest5"
    p {
        +"Client ID "
        input(InputType.text) {
            id = "clientID"
            value = "meow${Random.nextUInt()}-Web"
        }
    }
    p {
        +"Keep Alive Seconds "
        input(InputType.number) {
            id = "keepAlive"
            value = "15"
        }
    }
    p {
        +"Has Username "
        input(InputType.checkBox) {
            id = "hasUsername"
            checked = false
            onChangeFunction = {
                val hasUsername = document.getElementById("hasUsername")!!
                val checked = (hasUsername as HTMLInputElement).checked
                val username = document.getElementById("username") as HTMLInputElement
                username.disabled = !checked
                if (username.disabled) {
                    username.value = ""
                }
            }
        }
    }
    p {
        +"Username "
        input(InputType.text) {
            id = "username"
            value = ""
            disabled = true
        }
    }
    p {
        +"Has Password "
        input(InputType.checkBox) {
            id = "hasPassword"
            checked = false
            onChangeFunction = {
                val hasPassword = document.getElementById("hasPassword")!!
                val checked = (hasPassword as HTMLInputElement).checked
                val password = document.getElementById("password") as HTMLInputElement
                password.disabled = !checked
                if (password.disabled) {
                    password.value = ""
                }
            }
        }
    }
    p {
        +"Password "
        input(InputType.password) {
            id = "password"
            value = ""
            disabled = true
        }
    }
    p {
        +"Include Will "
        input(InputType.checkBox) {
            id = "includeWill"
            checked = false
            onChangeFunction = {
                val includeWill = document.getElementById("includeWill") as HTMLInputElement
                val willTopic = document.getElementById("willTopic") as HTMLInputElement
                val willPayload = document.getElementById("willPayload") as HTMLInputElement
                val willRetain = document.getElementById("willRetain") as HTMLInputElement
                val willQos = document.getElementById("willQos") as HTMLSelectElement
                willTopic.disabled = !includeWill.checked
                if (willTopic.disabled) {
                    willTopic.value = ""
                }
                willPayload.disabled = !includeWill.checked
                if (willPayload.disabled) {
                    willPayload.value = ""
                }
                willRetain.disabled = !includeWill.checked
                if (willRetain.disabled) {
                    willRetain.checked = false
                }
                willQos.disabled = !includeWill.checked
                if (willQos.disabled) {
                    willQos.value = "0"
                }
            }
        }
    }
    p {
        +"Will Topic "
        input(InputType.text) {
            id = "willTopic"
            value = ""
            disabled = true
        }
    }
    p {
        +"Will Payload "
        input(InputType.text) {
            id = "willPayload"
            value = ""
            disabled = true
        }
    }
    p {
        +"Will Retain "
        input(InputType.checkBox) {
            id = "willRetain"
            checked = false
            disabled = true
        }
    }
    p {
        +"Will QoS "
        select {
            id = "willQos"
            disabled = true
            option {
                label = "At Most Once (0)"
                value = "0"
            }
            option {
                label = "At Least Once (1)"
                value = "1"
            }
            option {
                label = "Exactly Once (2)"
                value = "2"
            }
        }
    }
    p {
        +"Clean Start "
        input(InputType.checkBox) {
            id = "cleanStart"
            checked = false
        }
    }
    p {
        +"MQTT 5 Extra Properties "
        input(InputType.checkBox) {
            id = "mqtt5Extras"
            checked = false
            onChangeFunction = {
                val mqtt5Extras = document.getElementById("mqtt5Extras") as HTMLInputElement
                val sessionExpiryIntervalSeconds =
                    document.getElementById("sessionExpiryIntervalSeconds") as HTMLInputElement
                val receiveMaximum = document.getElementById("receiveMaximum") as HTMLInputElement
                val maxPacketSize = document.getElementById("maxPacketSize") as HTMLInputElement
                val topicAliasMax = document.getElementById("topicAliasMax") as HTMLInputElement
                val requestResponseInformation =
                    document.getElementById("requestResponseInformation") as HTMLInputElement
                val requestProblemInformation = document.getElementById("requestProblemInformation") as HTMLInputElement
                sessionExpiryIntervalSeconds.disabled = !mqtt5Extras.checked
                if (sessionExpiryIntervalSeconds.disabled) {
                    sessionExpiryIntervalSeconds.value = "0"
                }
                receiveMaximum.disabled = !mqtt5Extras.checked
                if (receiveMaximum.disabled) {
                    receiveMaximum.value = ""
                }
                maxPacketSize.disabled = !mqtt5Extras.checked
                if (maxPacketSize.disabled) {
                    maxPacketSize.checked = false
                }
                topicAliasMax.disabled = !mqtt5Extras.checked
                if (topicAliasMax.disabled) {
                    topicAliasMax.value = "0"
                }
                requestResponseInformation.disabled = !mqtt5Extras.checked
                if (requestResponseInformation.disabled) {
                    requestResponseInformation.checked = false
                }
                requestProblemInformation.disabled = !mqtt5Extras.checked
                if (requestProblemInformation.disabled) {
                    requestProblemInformation.checked = false
                }
            }
        }
        p {
            +"Session Expiry Interval Seconds "
            input(InputType.number) {
                id = "sessionExpiryIntervalSeconds"
                value = "0"
                disabled = true
            }
        }
        p {
            +"Receive Maximum "
            input(InputType.number) {
                id = "receiveMaximum"
                disabled = true
            }
        }
        p {
            +"Max Packet Size "
            input(InputType.number) {
                id = "maxPacketSize"
                value = "2048"
                disabled = true
            }
        }
        p {
            +"Topic Alias Max "
            input(InputType.number) {
                id = "topicAliasMax"
                value = "100"
                disabled = true
            }
        }
        p {
            +"Request Response Information "
            input(InputType.checkBox) {
                id = "requestResponseInformation"
                disabled = true
            }
        }
        p {
            +"Request Problem Information "
            input(InputType.checkBox) {
                id = "requestProblemInformation"
                disabled = true
            }
        }
    }

}