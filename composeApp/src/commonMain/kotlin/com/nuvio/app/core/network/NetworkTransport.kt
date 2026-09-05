package com.nuvio.app.core.network

/**
 * Coarse classification of the network interface currently carrying app traffic.
 * Used by stream auto-play's NETWORK_QUALITY mode to prefer a smaller/lighter
 * stream on a metered connection and the best available one on WiFi.
 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    OTHER,
    UNKNOWN,
}

/**
 * Reports which network transport currently carries app traffic. This is a
 * synchronous, best-effort snapshot taken at stream-selection time - it is not
 * a live/observable connection monitor.
 */
expect object NetworkTransportMonitor {
    fun currentTransport(): NetworkTransport
}
