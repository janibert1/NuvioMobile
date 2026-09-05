package com.nuvio.app.core.network

actual object NetworkTransportMonitor {
    // TODO: Wire up real WiFi-vs-cellular detection (SCNetworkReachability or
    // Network.framework's NWPathMonitor) once this can be built and verified
    // against an actual Xcode toolchain - it was intentionally left as a safe
    // stub here because that could not be confirmed to compile in this session.
    // Until then this conservatively reports UNKNOWN, which the NETWORK_QUALITY
    // auto-play ranking treats the same as WIFI (no network-based ceiling beyond
    // the always-active device-resolution hard cap).
    actual fun currentTransport(): NetworkTransport = NetworkTransport.UNKNOWN
}
