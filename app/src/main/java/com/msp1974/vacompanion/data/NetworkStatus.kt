package com.msp1974.vacompanion.data

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

enum class NetworkStatus {
    Available,
    Unavailable
}

data class NetworkInfo(
    val status: NetworkStatus = NetworkStatus.Unavailable,
    val type: String = "None",
    val lastChanged: Long = 0,
    val disconnectCount: Long = 0
)

class NetworkStatusManager @Inject constructor(val context: Context) {

    private val _networkInfo = MutableStateFlow(NetworkInfo())
    val networkInfo: StateFlow<NetworkInfo> = _networkInfo.asStateFlow()

    val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onUnavailable() {
            Timber.w("Network unavailable")
            _networkInfo.update { info ->
                info.copy(
                    status = NetworkStatus.Unavailable,
                    type = "None",
                    lastChanged = System.currentTimeMillis()
                )
            }
        }

        override fun onAvailable(network: Network) {
            Timber.d("Network available")
            _networkInfo.update { info ->
                info.copy(
                    status = NetworkStatus.Available,
                    type = getNetworkType(connectivityManager, network),
                    lastChanged = System.currentTimeMillis()
                )
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkInfo.update { info ->
                info.copy(
                    type = getNetworkType(connectivityManager, network),
                    lastChanged = System.currentTimeMillis()
                )
            }
        }

        override fun onLost(network: Network) {
            Timber.w("Network lost  ")
            _networkInfo.update { info ->
                info.copy(
                    status = NetworkStatus.Unavailable,
                    type = "None",
                    lastChanged = System.currentTimeMillis(),
                    disconnectCount = info.disconnectCount + 1
                )
            }
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
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

}
