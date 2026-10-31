package com.nuvio.app.core.device

import android.content.res.Resources
import kotlin.math.max

actual object DeviceDisplayCapabilities {
    actual fun maxRenderableResolutionPx(): Int? =
        runCatching {
            val metrics = Resources.getSystem().displayMetrics
            max(metrics.widthPixels, metrics.heightPixels)
        }.getOrNull()
}
