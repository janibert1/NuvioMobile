package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleAutoSyncNoAudioAnalyzed
import com.nuvio.app.core.i18n.localizedSubtitleAutoSyncNoConfidentMatch
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}

/**
 * Fully automatic subtitle sync: no capture/pick-a-line interaction, just analyzes the
 * audio and applies the detected offset directly. Currently inert end-to-end (see
 * AudioVoiceActivityProvider's TODO — both platform actuals return no detected
 * activity, so this will reliably report [localizedSubtitleAutoSyncNoAudioAnalyzed]
 * until that's implemented) but the pipeline downstream of it — cue parsing, the
 * alignment algorithm, applying the result — is real and unit-tested against
 * synthetic data.
 */
internal fun PlayerScreenRuntime.runAutomaticSubtitleSync() {
    val subtitle = selectedAddonSubtitle ?: return
    val mediaUrl = activeSourceUrl.takeIf { it.isNotBlank() } ?: return
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L) return

    subtitleAutomaticSyncState = subtitleAutomaticSyncState.copy(isRunning = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val headers = sanitizePlaybackHeaders(activeSourceHeaders)
            val subtitleBody = httpGetTextWithHeaders(url = subtitle.url, headers = headers)
            val cues = PlayerSubtitleCueParser.parseWithRanges(subtitleBody, subtitle.url)
            if (cues.isEmpty()) return@runCatching null

            val windows = defaultVoiceActivitySampleWindows(durationMs)
            val voiceActivity = AudioVoiceActivityProvider.detectVoiceActivity(
                mediaUrl = mediaUrl,
                headers = headers,
                windows = windows,
            )
            if (voiceActivity.isEmpty()) return@runCatching null

            SubtitleAudioAligner.align(cues = cues, voiceActivity = voiceActivity)
        }
        result.fold(
            onSuccess = { alignment ->
                when {
                    alignment == null -> subtitleAutomaticSyncState = subtitleAutomaticSyncState.copy(
                        isRunning = false,
                        errorMessage = localizedSubtitleAutoSyncNoAudioAnalyzed(),
                    )
                    alignment.confidence < SUBTITLE_AUTO_SYNC_MIN_CONFIDENCE -> subtitleAutomaticSyncState =
                        subtitleAutomaticSyncState.copy(
                            isRunning = false,
                            lastResult = alignment,
                            errorMessage = localizedSubtitleAutoSyncNoConfidentMatch(),
                        )
                    else -> {
                        setSubtitleDelay(alignment.offsetMs)
                        subtitleAutomaticSyncState = subtitleAutomaticSyncState.copy(
                            isRunning = false,
                            lastResult = alignment,
                            errorMessage = null,
                        )
                    }
                }
            },
            onFailure = { error ->
                subtitleAutomaticSyncState = subtitleAutomaticSyncState.copy(
                    isRunning = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}
