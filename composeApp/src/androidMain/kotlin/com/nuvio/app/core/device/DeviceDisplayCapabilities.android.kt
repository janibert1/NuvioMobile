package com.nuvio.app.core.device

import android.content.res.Resources

actual object DeviceDisplayCapabilities {
    actual fun maxRenderableResolutionPx(): Int? {
        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return null
        return maxOf(width, height)
    }
}
