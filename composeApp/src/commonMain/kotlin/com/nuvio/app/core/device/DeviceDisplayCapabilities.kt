package com.nuvio.app.core.device

/**
 * Reports the current device's screen capability so stream auto-play can avoid
 * picking a resolution higher than the screen can meaningfully render.
 */
expect object DeviceDisplayCapabilities {
    /**
     * The longer edge of the device's screen, in physical pixels (e.g. ~1920 for
     * a 1080p-class screen, ~3840 for a 4K-class screen). Null if it could not be
     * determined, in which case callers should not apply a resolution cap.
     */
    fun maxRenderableResolutionPx(): Int?
}
