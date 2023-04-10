[![Contributors][contributors-shield]][contributors-url]  
[![Forks][forks-shield]][forks-url]  
[![Stargazers][stars-shield]][stars-url]  
[![Issues][issues-shield]][issues-url]  
[![MIT License][license-shield]][license-url]  
[![LinkedIn][linkedin-shield]][linkedin-url]


<!-- PROJECT LOGO -->  
<!--suppress ALL -->  

<br />  
<p align="center">  
<h3 align="center">MQTT Kotlin Multiplatform</h3>  

<p align="center">Buffer based kotlin multiplatform MQTT library.</p>  
<br />  
<!-- <a href="https://github.com/DitchOoM/buffer"><strong>Explore the docs »</strong></a> -->  
<br />  
<br />  
<!-- <a href="https://github.com/DitchOoM/buffer">View Demo</a>  
· -->  
<a href="https://github.com/DitchOoM/mqtt/issues">Report Bug</a>
<a href="https://github.com/DitchOoM/mqtt/issues">Request Feature</a>  
</p>  


<details open="open">  
  <summary>Table of Contents</summary>  
  <ol>  
    <li>  
      <a href="#about-the-project">About The Project</a>  
      <ul>  
        <li><a href="#runtime-dependencies">Runtime Dependencies</a></li>  
      </ul>  
      <ul>  
        <li><a href="#supported-platforms">Supported Platforms</a></li>  
      </ul>  
    </li>  
    <li><a href="#installation">Installation</a></li>  
    <li>  
      <a href="#usage">Usage</a>
    </li>  
    <li>  
      <a href="#building-locally">Building Locally</a>  
    </li>  
    <li><a href="#getting-started">Getting Started</a></li>  
    <li><a href="#roadmap">Roadmap</a></li>  
    <li><a href="#contributing">Contributing</a></li>  
    <li><a href="#license">License</a></li>  
  </ol>  
</details>  

## About The Project

This project aims to simplify managing an MQTT client between multiple platforms.

### Implementation notes

* A fully asynchronous, coroutines based implementation ensuring minimal memory footprint for low memory devices.
* Models are inherited, allowing for customization or custom protocols derived from MQTT without a full rewrite.

Buffer uses native buffers to pass to the socket or websocket module.

|        Platform        |        Native Buffer Type        |
|:----------------------:|:--------------------------------:|
|     Android / JVM      |            ByteBuffer            |
| iOS/macOS/tvOS/watchOS |              NSData              |
|    BrowserJS/NodeJS    | ArrayBuffer / SharedArrayBuffer  |


Socket uses native socket API's:

| Platform  |  Native Socket Impl  |
|:---------:|:--------------------:|
|Android/JVM|AsynchronousSocketChannel (or fallback to SocketChannel)|
| iOS/macOS/tvOS/watchOS | NWConnection |
|NodeJS|Net module|
|BrowserJS|unavailable|

The WebSocket uses:

| Platform  |                       WebSocket Impl                       |
|:---------:|:----------------------------------------------------------:|
|Android/JVM| AsynchronousSocketChannel (or fallback to SocketChannel)   |
| iOS/macOS/tvOS/watchOS |                        NWConnection                        |
|NodeJS|                         Net module                         |
|BrowserJS|                         WebSocket                          |

Persistence uses:

| Platform  |                                                           Persistence Impl                                                           |
|:---------:|:------------------------------------------------------------------------------------------------------------------------------------:|
|Android/JVM|                                                        SQLite via SQLdelight                                                         |
| iOS/macOS/tvOS/watchOS |                                               SQLite via SQLdelight using `-lsqlite3`                                                |
|NodeJS|                                                               InMemory                                                               |
|BrowserJS| IndexedDB, [SQLite upcoming](https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system/) |



### Runtime Dependencies

DitchOoM Kotlin Multiplatform Runtime Dependencies

* [Buffer](https://github.com/DitchOoM/buffer) - Allocate and manage a native buffer which can be passed to the socket.
* [Socket](https://github.com/DitchOoM/socket) - Connect to a TCP based MQTT broker.
* [websocket](https://github.com/DitchOoM/websocket) - Connect to a WebSocket based MQTT broker.

Official Kotlin Multiplatform Runtime Dependencies

* [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) - Library support for Kotlin coroutines with
  multiplatform support.

Other Kotlin Multiplatform Runtime Dependencies

* [sqldelight](https://github.com/cashapp/sqldelight) - Generates typesafe Kotlin APIs from your SQL statements.

### [Supported Platforms](https://kotlinlang.org/docs/reference/mpp-supported-platforms.html)

| Platform  | MQTT 3.1.1 (4) | MQTT 5.0 | LWT | SSL / TLS | Message Persistence | Automatic Reconnect | Offline Buffering | WebSocket Support | Standard TCP Support | Asynchronous API | Coroutines API | High Availability |  IPC / Worker Support | 
|:---------:|:--------------:|:--------:|:---:|:---------:|:-------------------:|:-------------------:|:-----------------:|:-----------------:|:--------------------:|:----------------:|:--------------:|:-----------------:|:---------------------:|
|   `JVM`   |       🚀       |    🚀    | 🚀  |    🚀     |         🚀          |         🚀          |        🚀         |        🚀         |          🚀          |        📝        |       🚀       |        🚀        |           ❓           |
| `Browser` |       🚀       |    🚀    | 🚀  |    🚀     |         🚀          |         🚀          |        🚀         |        🚀         |          ⛔           |        📝        |       🚀       |        🚀       |          🚀           |
| `Node.JS` |       🚀       |    🚀    | 🚀  |    🚀     |         📝          |         🚀          |        🚀         |        🚀         |          🚀          |        📝        |       🚀       |        🚀        |           🧪           |
| `Android` |       🚀       |    🚀    | 🚀  |    🚀     |         🚀          |         🚀          |        🚀         |        🚀         |          🚀          |        📝        |       🚀       |        🚀        |           🚀           |
|   `iOS`   |       🚀       |    🚀    | 🚀  |    🚀     |         🚀          |         🚀          |        🚀         |        🚀         |          🚀          |        📝        |       🚀       |        🚀        |           ❓           |
|  `MacOS`  |       🚀       |    🚀    | 🚀  |    🚀     |         🚀          |         🚀          |        🚀         |        🚀         |          🚀          |        📝        |       🚀       |        🚀        |           ❓           |
| `WatchOS` |       📴       |    📴    | 📴  |    📴     |         📴          |         📴          |        📴         |        📴         |          📴          |        📝        |       📴       |        📴        |           ❓           |
|  `TvOS`   |       📴       |    📴    | 📴  |    📴     |         📴          |         📴          |        📴         |        📴         |          📴          |        📝        |       📴       |        📴        |           ❓           |
| `WatchOS` |       📴       |    📴    | 📴  |    📴     |         📴          |         📴          |        📴         |        📴         |          📴          |        📝        |       📴       |        📴        |           ❓           |

> 🚀 = Ready.
>
> 📝 = TODO or Coming soon
>
> 📴 = Disabled for now (can be enabled easily, just disabled to speed up build times). File an issue if you need it and
> it can be easily enabled.
> 
> 🧪 = Probably will work, but currently undocumented
>
> ❓= Probably unsupported, no current plans to support
> 
> ⛔ = Impossible due to API issues.

## Installation

### Gradle

- [Add `implementation("com.ditchoom:mqtt-client:$version")` to your `build.gradle` dependencies](https://search.maven.org/artifact/com.ditchoom/mqtt-client)

Add either 3.1.1(4) or 5 based on what you need (or both)

- [Add `implementation("com.ditchoom:mqtt-4-models:$version")` to your `build.gradle` dependencies](https://search.maven.org/artifact/com.ditchoom/mqtt-4-models)
- [Add `implementation("com.ditchoom:mqtt-5-models:$version")` to your `build.gradle` dependencies](https://search.maven.org/artifact/com.ditchoom/mqtt-5-models)

- Coming Soon

NPM + Cocoapods

## Usage

### Quick Start

Connect to an MQTT Broker (falling back to WebSocket). Subscribe to a topic, publish a message, unsubscribe a topic and
shutdown.

Suspending API

```kotlin
// Get a reference to the service, keep this for the process
val service = MqttService.buildNewService(ipcEnabled = true, androidContextOrWorkerOrNull, inMemory = false)

val socketEndpoint = MqttConnectionOptions.SocketConnection(host = "test.mosquitto.org", port = 1883)
val wsEndpoint = MqttConnectionOptions.WebSocketConnectionOptions(host = "test.mosquitto.org", port = 8080)
val connections = listOf(socketEndpoint, wsEndpoint)
val connectionRequest = ConnectionRequest(clientId = "testClient")

val client = service.addBrokerAndStartClient(connections, connectionRequest)
val subscribeOperation = client.subscribe("test/+", QualityOfService.AT_LEAST_ONCE)

// optional, await for suback before proceeding
val suback = subscribeOperation.subAck.await()
// optional, subscribe to incoming publish on the topic
val topicFlow = subscribeOperation.subscriptions.values.first()

val payloadBuffer = PlatformBuffer.allocate(4, AllocationZone.SharedMemory)
//Cast to JvmBuffer/JsBuffer/DataBuffer and retrieve underlying ByteBuffer/ArrayBuffer/NSData to modify contents
payloadBuffer.writeString("taco") // just write utf8 string data for now
val pubOperation = client.publish("test/123", QualityOfService.EXACTLY_ONCE, payloadBuffer)
pubOperation.awaitAll() // suspend until 

val unsubscribeOperation = client.unsubscribe("test/+")
unsubscribeOperation.unsubAck.await()

client.shutdown()

```

### `MqttService`

`MqttService` provides you with an API to create, read and delete `MqttBroker` instances. `MqttBroker` instances are
used to identify an `MqttClient`. One of the main advantages `MqttService` is to manage an always connected service.
As a consumer of this api, you can safely ignore any network error states and trust the service will automatically
reconnect, transmit and acknowledge messages.

Suspending API - Get a reference to the MQTT Service

```kotlin
val service = MqttService.buildNewService(
    // Boolean value, no default. // if set to true, see details below for additional required Android/JS configuration.
    ipcEnabled,
    // Any? value, defaults to null. Pass the android context or the browser based Worker context. Required for IPC.
    androidContextOrAbstractWorker,
    // Boolean value, defaults to false. IPC will not work correctly if set to false.
    useMemoryPersistence
)

```

Suspending API - MqttService - Managing a broker:

```kotlin
interface MqttService {
    // Add a broker. This will persist to the database.
    suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker

    // Get all saved brokers from the database.
    suspend fun allBrokers(): Collection<MqttBroker>

    // Remove Broker by `MqttBroker.brokerId` and `MqttBroker.protocolVersion`
    suspend fun removeBroker(brokerId: Int, protocolVersion: Byte)

    // Add the broker to persistence, start the connection and return the MqttClient
    suspend fun addAndStartClient(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttClient

    // Get a MqttClient by the broker
    suspend fun getClient(broker: MqttBroker): MqttClient?

    // Start the Mqtt Client and keep it connected
    suspend fun start(broker: MqttBroker)

    // Start all the brokers persisted
    suspend fun start()

    // Stop all the brokers that are running
    suspend fun stop()

    // Stop a particular client connected to this broker
    suspend fun stop(broker: MqttBroker)
}
```

### `MqttClient`

`MqttClient` has several methods which you can check in the source code. Here's a few of the important ones:

Suspending API - MqttClient - Publishing a simple message:

```kotlin

interface MqttClient {
    // Finer grained control over the allocation of a control packet
    val packetFactory: ControlPacketFactory
    val broker: MqttBroker

    // Get the current connection acknowledgment or null if not connected
    suspend fun currentConnectionAcknowledgment(): IConnectionAcknowledgment?

    // suspend until connected, returning the connection acknowledgment
    suspend fun awaitConnectivity(): IConnectionAcknowledgment

    // publish a message
    suspend fun publish(
        topicName: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        payload: ReadBuffer? = null,
        retain: Boolean = false
    ): PublishOperation

    // Flow of messages that match a particular topic
    fun observe(filter: Topic): Flow<IPublishMessage>

    // Subscribe to a topic
    suspend fun subscribe(topicFilter: String, maxQos: QualityOfService): SubscribeOperation

    // Unsubscribe to a topic
    suspend fun unsubscribe(unsub: IUnsubscribeRequest): UnsubscribeOperation

    // send a disconnect packet to the server, potentially kicking off a reconnect
    suspend fun sendDisconnect()

    // shutdown this client and stop reconnecting
    suspend fun shutdown(sendDisconnect: Boolean = true)
}
```

### IPC

Multi-process IPC is fully supported on Android and JS, while silently ignored on the other platforms due to technical
limitations. IPC can help the client stay connected, continuing to transmit any messages even if the calling process
dies. With IPC
enabled, an Android Activity or Web Browser Context can crash and restart without affecting the process which manages
the MQTT service. This means the MQTT service can continue to process messages without restarting.

For Android it will work straight out of the box. Getting the MqttService with `ipcEnabled = true` will automatically
register the android service and use AIDL to communicate with it.

However you can customize the process name by overriding the manifest:

```xml
<service
        android:name="com.ditchoom.mqtt.client.ipc.MqttManagerService"
        android:process=":sync"/>
```


Non-Mqtt Context (ex. Activity or ViewModel)
```kotlin
// pass in the abstract worker reference
val service: MqttService = MqttService.buildNewService(true, applicationContext)
```

For JS, your Abstract Worker (Dedicated Worker, Service Worker or Shared Worker) needs to call:

```kotlin
private var ipcServer: JsRemoteMqttServiceWorker? = null
self.oninstall = { // for service workers, otherwise just call `buildMqttServiceIPCServer(false)` before setting the onmessage callback
    val event = it.unsafeCast<ExtendableEvent>()
    event.waitUntil(GlobalScope.promise {
        ipcServer = buildMqttServiceIPCServer(false)
    })
}
self.onmessage = {
    ipcServer?.processIncomingMessage(it)
}
```
Browser Window Context
```kotlin
// pass in the abstract worker reference
val service: MqttService = MqttService.buildNewService(true, worker)
```

## Building Locally

- `git clone git@github.com:DitchOoM/mqtt.git`
- Open cloned directory with [Intellij IDEA](https://www.jetbrains.com/idea/download).
    - Be sure  
      to [open with gradle](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)

## Roadmap

See the [open issues](https://github.com/DitchOoM/mqtt/issues) for a list of proposed features (  
and known issues).

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire,  
and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

[contributors-shield]: https://img.shields.io/github/contributors/DitchOoM/mqtt.svg?style=for-the-badge

[contributors-url]: https://github.com/DitchOoM/mqtt/graphs/contributors

[forks-shield]: https://img.shields.io/github/forks/DitchOoM/mqtt.svg?style=for-the-badge

[forks-url]: https://github.com/DitchOoM/mqtt/network/members

[stars-shield]: https://img.shields.io/github/stars/DitchOoM/mqtt.svg?style=for-the-badge

[stars-url]: https://github.com/DitchOoM/mqtt/stargazers

[issues-shield]: https://img.shields.io/github/issues/DitchOoM/mqtt.svg?style=for-the-badge

[issues-url]: https://github.com/DitchOoM/mqtt/issues

[license-shield]: https://img.shields.io/github/license/DitchOoM/mqtt.svg?style=for-the-badge

[license-url]: https://github.com/DitchOoM/mqtt/blob/master/LICENSE.md

[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555

[linkedin-url]: https://www.linkedin.com/in/thebehera

[maven-central]: https://search.maven.org/search?q=com.ditchoom

[npm]: https://www.npmjs.com/search?q=ditchoom-mqtt-client

[cocoapods]: https://cocoapods.org/pods/DitchOoM-mqtt-client

[apt]: https://packages.ubuntu.com/search?keywords=ditchoom&searchon=names&suite=groovy&section=all

[yum]: https://pkgs.org/search/?q=DitchOoM-mqtt-client

[chocolately]: https://chocolatey.org/packages?q=DitchOoM-mqtt-client