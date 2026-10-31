package com.nuvio.app.core.device

expect object DeviceDisplayCapabilities {
    /**
     * Physical pixel size of the screen's longer edge, or null if the host
     * environment doesn't expose physical display metrics (e.g. desktop/unit tests).
     */
    fun maxRenderableResolutionPx(): Int?
}
