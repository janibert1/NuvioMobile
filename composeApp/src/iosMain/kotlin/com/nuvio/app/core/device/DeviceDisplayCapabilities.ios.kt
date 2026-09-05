package com.nuvio.app.core.device

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen

actual object DeviceDisplayCapabilities {
    @OptIn(ExperimentalForeignApi::class)
    actual fun maxRenderableResolutionPx(): Int? {
        val screen = UIScreen.mainScreen
        val scale = screen.nativeScale
        val size = screen.bounds.useContents { size }
        val width = size.width * scale
        val height = size.height * scale
        if (width <= 0 || height <= 0) return null
        return maxOf(width, height).toInt()
    }
}
