package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.launch
import timber.log.Timber

class NetworkSensor(private val context: Context) : Sensor {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "network",
            type = -3, // Not a standard Android sensor type
            name = "Network Sensor",
        )
    }

    override var onUpdate: ((String, Any) -> Unit)? = null
    private val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    private var currentState = NetworkState()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onUnavailable() {
            Timber.w("Network unavailable")
            updateNetworkState(currentState.copy(
                status = NetworkStatus.Unavailable,
                type = "None",
                lastChanged = System.currentTimeMillis()
            ))
        }

        override fun onAvailable(network: Network) {
            Timber.d("Network available")
            updateNetworkState(currentState.copy(
                status = NetworkStatus.Available,
                type = getNetworkType(connectivityManager, network),
                lastChanged = System.currentTimeMillis()
            ))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateNetworkState(currentState.copy(
                type = getNetworkType(connectivityManager, network),
                lastChanged = System.currentTimeMillis()
            ))
        }

        override fun onLost(network: Network) {
            Timber.w("Network lost")
            updateNetworkState(currentState.copy(
                status = NetworkStatus.Unavailable,
                type = "None",
                lastChanged = System.currentTimeMillis(),
                disconnectCount = currentState.disconnectCount + 1
            ))
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun updateNetworkState(newState: NetworkState) {
        if (newState != currentState) {
            currentState = newState
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentState)
            }
        }
    }

    private fun getNetworkType(connectivityManager: ConnectivityManager, network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "None"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
    }

    override fun hasSensor(context: Context): Boolean = true

    override fun requiredPermissions(): Array<String> = emptyArray()

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        // No-op: Handled by NetworkCallback
    }

    override fun stop() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}
