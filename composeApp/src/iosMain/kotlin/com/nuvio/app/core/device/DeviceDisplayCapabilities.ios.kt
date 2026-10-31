package com.nuvio.app.core.device

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
actual object DeviceDisplayCapabilities {
    actual fun maxRenderableResolutionPx(): Int? =
        runCatching {
            val screen = UIScreen.mainScreen
            val scale = screen.nativeScale
            val bounds = screen.bounds
            val (width, height) = bounds.useContents { size.width to size.height }
            (max(width, height) * scale).roundToInt()
        }.getOrNull()
}
