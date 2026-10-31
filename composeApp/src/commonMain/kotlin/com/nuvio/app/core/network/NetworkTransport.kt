package com.nuvio.app.core.network

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    OTHER,
    UNKNOWN,
}

expect object NetworkTransportMonitor {
    fun currentTransport(): NetworkTransport
}
