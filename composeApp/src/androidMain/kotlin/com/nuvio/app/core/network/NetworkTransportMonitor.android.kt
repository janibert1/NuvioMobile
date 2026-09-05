package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

actual object NetworkTransportMonitor {
    private var connectivityManager: ConnectivityManager? = null

    fun initialize(context: Context) {
        connectivityManager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    actual fun currentTransport(): NetworkTransport {
        val manager = connectivityManager ?: return NetworkTransport.UNKNOWN
        val network = manager.activeNetwork ?: return NetworkTransport.UNKNOWN
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkTransport.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.OTHER
        }
    }
}
