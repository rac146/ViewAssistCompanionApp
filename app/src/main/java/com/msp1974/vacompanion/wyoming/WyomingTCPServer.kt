package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.satellite.Satellite
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.Executors

interface IEvents {
    fun onEvent(event: String, data: JsonObject)
    fun onState(state: ServerState, restartIfStopped: Boolean = true)
}

data class Connection(
    val id: String,
    val handler: WyomingClientHandler,
)

data class MessageQueueItem(
    val clientId: String,
    val message: WyomingPacket
)

abstract class WyomingTCPServer(private val context: Context, val config: APPConfig): IEvents {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private var runServer: Boolean = true
    var satellite: Satellite? = null
    private val clients = mutableMapOf<String, Connection>()
    private lateinit var zeroconf: Zeroconf
    private var serverSocket: ServerSocket? = null
    private var restartIfStopped: Boolean = false

    private var deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context, config).getDeviceInfo()
    private val infoBuilder: WyomingInfoBuilder = WyomingInfoBuilder(context, config, deviceInfo)

    var state: ServerState = ServerState.STOPPED
        set(value) {
            field = value
            onState(value, restartIfStopped)
        }

    suspend fun startServer() {
        val exec = Executors.newCachedThreadPool()
        val selector = ActorSelectorManager(exec.asCoroutineDispatcher())

        state = ServerState.STARTING
        Timber.d("Wyoming TCP Server starting")


        try {
            serverSocket =
                aSocket(selector).tcp().bind("0.0.0.0", config.serverPort)
            Timber.d("Wyoming TCP Server started and listening at ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("Server Error: ${e.toString()}")
            return
        }


        Timber.d("Starting zeroconf")
        try {
            zeroconf = Zeroconf(context, config.uuid)
            registerNSD()
        } catch (e: Exception) {
            Timber.e("Error starting zeroconf: $e")
            state = ServerState.ERRORED
            return
        }

        withContext(Dispatchers.IO) {
            try {
                state = ServerState.RUNNING
                restartIfStopped = true
                while (runServer) {
                    val socket = serverSocket?.accept()

                    val data = buildJsonObject {
                        put("remoteId", socket?.remoteAddress.toString())
                    }

                    val client: WyomingClientHandler = object : WyomingClientHandler(scope, socket!!) {
                        override suspend fun onClientDisconnected(clientId: String) {

                            if (clientId in clients) {
                                val client = clients[clientId]
                                client?.handler?.stop()
                                clients.remove(clientId)
                            }
                            Timber.d("Client disconnected: $clientId.  Total: ${clients.size}")


                            if (clients.isEmpty()) {
                                Timber.d("No clients connected")
                                scope.launch {
                                    // Stop satellite if connection lost for more than 15s
                                    delay(15000)
                                    if (clients.isEmpty()) {
                                        stopSatellite()
                                    }
                                }
                            }
                        }

                        override suspend fun onWyomingMessage(
                            clientId: String,
                            message: WyomingPacket
                        ) {
                            try {
                                messageHandler(clientId, message)
                            } catch (e: Exception) {
                                Timber.e("Error processing message: $e")
                            }
                        }
                    }

                    client.run()
                    val id = socket.remoteAddress.port().toString()
                    clients[id] = Connection(id, client)
                    Timber.d("Client connected: ${socket.remoteAddress}.  Total: ${clients.size}")
                    onEvent("client_connected", data)

                    yield()

                }
            } catch (e: Throwable) {
                ensureActive()
                Timber.e("Server Error: ${e.toString()}")
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Stopping Wyoming TCP Server...")
                    state = ServerState.STOPPING
                    stopSatellite()
                    try {
                        if (!clients.isEmpty()) {
                            clients.forEach { client ->
                                Timber.d("Stopping client: ${client.key}")
                                client.value.handler.stop()
                            }
                            clients.clear()
                        }
                        serverSocket?.close()
                        unregisterNSD()
                    } catch (e: Exception) {
                        Timber.e("Error when stopping server: $e")
                    }
                    state = ServerState.STOPPED
                    Timber.i("Wyoming TCP Server stopped")
                }
            }
        }
    }

    fun stopServer(restartAfterStop: Boolean = false) {
        restartIfStopped = restartAfterStop
        runServer = false
        serverSocket?.close()
    }

    private fun registerNSD() {
        zeroconf.registerService(config.serverPort)
    }

    private fun unregisterNSD() {
        zeroconf.unregisterService()
    }

    private suspend fun messageHandler(clientId: String, packet: WyomingPacket) {
        if (packet.type !in IGNORED_LOG_EVENTS) {
            Timber.d("Received <- ${clientId}: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                "ping" -> sendPong(clientId)
                "pong" -> {}
                "describe" -> sendInfo(clientId)
                "capabilities" -> sendCapabilities(clientId)
                "custom-event" -> {
                    if (!processPreSatelliteCustomEvent(packet)) {
                        processSatelliteMessage(clientId, packet)
                    }
                }
                "run-satellite" -> {
                    startSatellite(clientId)
                }
                "pause-satellite" -> stopSatellite()
                else -> processSatelliteMessage(clientId, packet)
            }
        } catch (ex: Exception) {
            Timber.e("Error processing event ${packet.type}: $ex")
        }
    }

    private fun processPreSatelliteCustomEvent(packet: WyomingPacket): Boolean {
        if (satellite != null) return false

        val eventType = packet.getProp("event_type")
        if (eventType != "settings") return false

        val settings = packet.getProp("settings")
        if (settings.isBlank()) return false

        // Compatibility path for older integrations that send settings before run-satellite.
        config.processSettings(settings)
        Timber.d("Processed pre-satellite settings event")
        return true
    }

    private suspend fun processSatelliteMessage(clientId: String, packet: WyomingPacket) {
        var retryCount = 2
        var processed = false
        while (retryCount > 0) {
            if (satellite != null && satellite?.state != SatelliteState.STOPPED) {
                satellite?.processMessage(packet)
                processed = true
                break
            } else {
                retryCount--
                delay(1000)
            }
        }
        if (!processed) {
            Timber.w("Cannot process message ${packet.toMap()}")
            when {
                satellite == null -> Timber.w("Satellite is null")
                clientId != satellite?.clientId -> Timber.w("Client id does not match satellite id")
                satellite?.state == SatelliteState.STOPPED -> Timber.w("Satellite is stopped")
            }
        }
    }

    suspend fun sendPong(clientId: String) {
        respondToGenericMessage(clientId, "pong", buildJsonObject { put("text", "") })
    }

    suspend fun sendInfo(clientId: String) {
        respondToGenericMessage(clientId, "info", infoBuilder.buildInfo())
    }

    suspend fun sendCapabilities(clientId: String) {
        respondToGenericMessage(clientId, "capabilities", DeviceCapabilitiesManager.toJson(deviceInfo))
    }

    private suspend fun   startSatellite(clientId: String) {
        Timber.d("Processing run satellite")

        val serverIP = clients[clientId]?.handler?.clientIP ?: ""

        if (config.pairedDeviceID.isEmpty()) {
            config.pairedDeviceID = serverIP
        } else if (!isValidServer(serverIP)) {
            Timber.e("Non paired server attempted to start satellite.  Paired to ${config.pairedDeviceID}, attempting server: $serverIP")
            return
        }

        if (satellite != null) {
            if (satellite?.state == SatelliteState.RUNNING) {
                Timber.d("Satellite already running - updating clientId")
                satellite?.clientId = clientId
                return
            } else if (satellite?.state == SatelliteState.STOPPING) {
                try {
                    Timber.d("Satellite shutting down.  Waiting...")
                    withTimeout(2000) {
                        while (satellite != null) {
                            delay(100)
                        }
                    }
                } catch (e: Exception) {
                    Timber.w("Satellite taking too long to stop.  Terminating")
                    stopSatellite()
                }
            } else {
                Timber.d("Satellite in a bad state - stopping and starting again")
                stopSatellite()
            }
        }
        try {
            Timber.d("Starting satellite")
            config.homeAssistantConnectedIP = serverIP
            satellite = object: Satellite(context, config, scope, clientId, deviceInfo) {
                override fun onEvent(event: String, data: JsonObject) {
                    Timber.d("Satellite event: $event")
                }

                override fun sendSatelliteMessage(
                    clientId: String,
                    type: String,
                    data: JsonObject,
                    payload: ByteArray
                ) {
                    sendMessage(clientId, type, data, payload)
                }
            }.also {
                unregisterNSD()
                scope.launch {
                    it.start()
                }
            }
        } catch (e: Exception) {
            Timber.e("Error starting satellite: $e")
        }
    }

    private fun isValidServer(ipAddr: String): Boolean {
        return config.pairedDeviceID == "" || config.pairedDeviceID == ipAddr
    }

    private suspend fun stopSatellite() {
        if (satellite != null) {
            val satId = satellite?.clientId
            satellite?.stop()
            satellite = null
            withContext(Dispatchers.IO) {
                clients[satId]?.handler?.socket?.close()
                clients.remove(satId)
            }
            registerNSD()
        }
    }

    private suspend fun respondToGenericMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        val packet = WyomingPacket(type, data, payload)
        if (type !in IGNORED_LOG_EVENTS) {
            Timber.d("Sending -> $clientId: ${packet.toMap()}")
        }
        clients[clientId]?.handler?.writeMessage(packet)
    }

    fun sendMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        if (satellite != null && clientId == satellite?.clientId) {
            scope.launch {
                val packet = WyomingPacket(type, data, payload)
                if (type !in IGNORED_LOG_EVENTS) {
                    Timber.d("Sending -> $clientId: ${packet.toMap()}")
                }
                clients[clientId]?.handler?.writeMessage(packet)
            }
        }
    }

    companion object {
        private val IGNORED_LOG_EVENTS = setOf("ping", "pong", "audio-chunk")
    }
}
