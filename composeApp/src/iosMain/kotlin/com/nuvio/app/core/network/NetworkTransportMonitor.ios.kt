package com.nuvio.app.core.network

actual object NetworkTransportMonitor {
    /**
     * Stubs to UNKNOWN on iOS until SCNetworkReachability / NWPathMonitor can
     * be verified with the Xcode toolchain. UNKNOWN is treated the same as WIFI.
     */
    actual fun currentTransport(): NetworkTransport = NetworkTransport.UNKNOWN
}
