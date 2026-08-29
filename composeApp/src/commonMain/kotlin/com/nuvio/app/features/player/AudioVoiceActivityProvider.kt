package com.nuvio.app.features.player

/**
 * Platform-specific: decodes a handful of short windows from a media URL's audio track
 * and returns detected speech intervals for each, for SubtitleAudioAligner to correlate
 * against subtitle cue timing.
 *
 * Deliberately window-sampled, not a full-file decode: analyzing a whole 2-hour movie's
 * audio just to find a sync offset would be slow, battery-heavy, and wasteful of
 * bandwidth on a streamed source. A handful of representative windows spread across the
 * file (dialogue-heavy stretches are common throughout most content) is enough to find
 * a confident constant offset — see SubtitleAudioAligner's performance note for why the
 * alignment scan itself also depends on this staying small.
 *
 * *** STATUS: interface + call site only. Not implemented on either platform yet — see
 * the .android.kt / .ios.kt actuals, both currently return an empty list. This is the
 * one remaining piece before automatic sync actually works end-to-end; everything
 * upstream of it (parsing, the alignment algorithm, the PlayerScreenRuntime wiring) is
 * built and tested against synthetic data. Real implementation needs, per platform:
 *  - Android: MediaExtractor + MediaCodec to decode the requested time ranges to PCM.
 *  - iOS: AVAssetReader with an output settings dict requesting linear PCM, seeked to
 *    each requested range.
 *  - A VAD over the decoded PCM. Start with simple short-time energy + zero-crossing-
 *    rate thresholding against a rolling noise floor (matches this codebase's stated
 *    preference for hand-written, dependency-free logic over pulling in an ML VAD model
 *    — see the on-device recommender's own commit history) before reaching for
 *    anything heavier.
 */
expect object AudioVoiceActivityProvider {
    /**
     * Requests voice-activity detection over [windows] of [mediaUrl]'s audio track.
     * Returns detected speech intervals (absolute media time, matching [windows]'
     * time base) — empty on failure or when nothing conclusive was found. Never throws.
     */
    suspend fun detectVoiceActivity(
        mediaUrl: String,
        headers: Map<String, String>,
        windows: List<LongRange>,
    ): List<VoiceActivitySample>
}

/**
 * Default window plan: a handful of ~90s windows spread across the file rather than one
 * block at the start (opening minutes are disproportionately likely to be music/logos/
 * quiet establishing shots with little dialogue to correlate against).
 */
fun defaultVoiceActivitySampleWindows(durationMs: Long, windowMs: Long = 90_000L, windowCount: Int = 4): List<LongRange> {
    if (durationMs <= 0L) return emptyList()
    val usableDurationMs = durationMs.coerceAtLeast(windowMs)
    val spacing = usableDurationMs / (windowCount + 1)
    return (1..windowCount).map { i ->
        val start = (spacing * i).coerceIn(0L, (durationMs - windowMs).coerceAtLeast(0L))
        start until (start + windowMs).coerceAtMost(durationMs)
    }
}
