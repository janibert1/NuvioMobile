package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

actual object NetworkTransportMonitor {
    private var connectivityManager: ConnectivityManager? = null

    fun initialize(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    actual fun currentTransport(): NetworkTransport {
        val cm = connectivityManager ?: return NetworkTransport.UNKNOWN
        val active = cm.activeNetwork ?: return NetworkTransport.UNKNOWN
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkTransport.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.OTHER
        }
    }
}
