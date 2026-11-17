package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.transcode.AdaptiveQualityController
import com.nuvio.app.core.transcode.TranscodeLadder
import com.nuvio.app.core.transcode.TranscodeSpeedProbe
import com.nuvio.app.core.transcode.TranscodeTier
import com.nuvio.app.core.transcode.TranscodeUrls
import com.nuvio.app.isIos
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Adaptive quality for the transcode proxy.
 *
 * - Android/desktop: the player gets `master.m3u8` and its HLS stack (ExoPlayer) does the ABR natively.
 * - iOS (MPV/ffmpeg hls demuxer does not switch renditions): the player gets one per-bitrate playlist and
 *   [AdaptiveQualityController] reloads another tier at the current position when the network changes.
 */

/** URL the player should start with for [url] (identity for non-transcode URLs). */
internal fun transcodeStartUrl(url: String): String = when {
    !TranscodeUrls.isTranscode(url) -> url
    !isIos -> TranscodeUrls.asMaster(url)
    TranscodeUrls.isMaster(url) ->
        TranscodeUrls.withTier(url, TranscodeLadder.selectTier(TranscodeSpeedProbe.cachedSpeedMbps(url)))
    else -> url
}

internal val PlayerScreenRuntime.isTranscodeSource: Boolean
    get() = activeTorrentInfoHash == null && TranscodeUrls.isTranscode(activeSourceUrl)

/** Short label for the player header, e.g. "Auto · 5 Mbps" or "Low · 0.8 Mbps". Null when not a transcode source. */
internal val PlayerScreenRuntime.transcodeQualityLabel: String?
    get() {
        if (!isTranscodeSource) return null
        val manual = transcodeManualTier
        val tier = TranscodeUrls.tierOf(activeSourceUrl)
        return when {
            manual != null -> manual.label
            tier != null -> "Auto · ${tier.label.substringAfter("· ")}"
            else -> "Auto"
        }
    }

/** Picks a fixed [tier], or automatic quality when null. Keeps position, play/pause and track choices. */
internal fun PlayerScreenRuntime.selectTranscodeQuality(tier: TranscodeTier?) {
    if (!isTranscodeSource) return
    transcodeManualTier = tier
    val target = when {
        tier != null -> TranscodeUrls.withTier(activeSourceUrl, tier)
        else -> {
            val auto = TranscodeUrls.tierOf(activeSourceUrl)
                ?: TranscodeLadder.selectTier(TranscodeSpeedProbe.cachedSpeedMbps(activeSourceUrl))
            transcodeController = null // fresh warmup + state for the new mode
            if (isIos) TranscodeUrls.withTier(activeSourceUrl, auto) else TranscodeUrls.asMaster(activeSourceUrl)
        }
    }
    reloadTranscodeSource(target)
}

private fun PlayerScreenRuntime.reloadTranscodeSource(url: String) {
    if (url == activeSourceUrl) return
    val positionMs = if (initialSeekApplied) playbackSnapshot.positionMs.coerceAtLeast(0L) else activeInitialPositionMs
    activeSourceUrl = url
    activeInitialPositionMs = positionMs
    activeInitialProgressFraction = null
    trackPreferenceRestoreApplied = false
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
internal fun PlayerScreenRuntime.BindAdaptiveQualityEffect() {
    val url = activeSourceUrl
    val manual = transcodeManualTier
    LaunchedEffect(url, manual) {
        if (!isIos || manual != null || activeTorrentInfoHash != null) return@LaunchedEffect
        val startTier = TranscodeUrls.tierOf(url) ?: return@LaunchedEffect
        val contentKey = TranscodeUrls.asMaster(url)

        var controller = transcodeController
        if (controller == null || transcodeControllerKey != contentKey) {
            controller = AdaptiveQualityController(startTier, nowMs())
            transcodeController = controller
            transcodeControllerKey = contentKey
            // Startup: pick the tier for the real phone -> proxy throughput; cheap this early (nothing buffered yet).
            val speed = TranscodeSpeedProbe.cachedSpeedMbps(url) ?: TranscodeSpeedProbe.measureStartup(url)
            val target = TranscodeLadder.selectTier(speed)
            if (speed != null && target.videoKbps != startTier.videoKbps && playbackSnapshot.positionMs < STARTUP_CORRECTION_MAX_POSITION_MS) {
                controller.reset(target, nowMs())
                reloadTranscodeSource(TranscodeUrls.withTier(url, target))
                return@LaunchedEffect
            }
        } else if (controller.currentTier.videoKbps != startTier.videoKbps) {
            controller.reset(startTier, nowMs())
        }

        var pendingProbe: Double? = null
        var probeJob: Job? = null
        var lastProbeAt = nowMs()
        try {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                val snap = playbackSnapshot
                val now = nowMs()
                val probe = pendingProbe
                pendingProbe = null
                if (shouldPlay && now - lastProbeAt >= PROBE_INTERVAL_MS && probeJob?.isActive != true) {
                    lastProbeAt = now
                    probeJob = launch { pendingProbe = TranscodeSpeedProbe.measureLive(url) }
                }
                val buffer = if (snap.bufferedPositionMs > 0L) snap.bufferedPositionMs - snap.positionMs else null
                val next = controller.onSample(
                    AdaptiveQualityController.Sample(
                        nowMs = now,
                        isPlaying = shouldPlay && !snap.isEnded,
                        isLoading = snap.isLoading,
                        bufferAheadMs = buffer,
                        probeMbps = probe,
                    ),
                )
                if (next != null) {
                    reloadTranscodeSource(TranscodeUrls.withTier(url, next))
                    return@LaunchedEffect
                }
            }
        } finally {
            probeJob?.cancel()
        }
    }
}

private const val SAMPLE_INTERVAL_MS = 2_000L
private const val PROBE_INTERVAL_MS = 15_000L
private const val STARTUP_CORRECTION_MAX_POSITION_MS = 15_000L
