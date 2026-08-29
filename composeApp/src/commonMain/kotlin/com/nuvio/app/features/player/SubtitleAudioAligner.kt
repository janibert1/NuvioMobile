package com.nuvio.app.features.player

import kotlin.math.max
import kotlin.math.min

/**
 * Finds the constant time offset that best aligns a subtitle track to the audio it's
 * meant to caption, without any user interaction — the automatic counterpart to the
 * existing manual "capture + pick a line" flow in PlayerScreenRuntimeSubtitleActions.
 *
 * Same idea as the alignment step in tools like ffsubsync/alass: build two binary
 * "is something happening here" signals sampled at a fixed frame rate — one from the
 * subtitle cues' [start, end) windows, one from detected voice-activity intervals in
 * the decoded audio — then slide one against the other and pick the offset with the
 * highest overlap. Deliberately scoped to a single constant offset (not a linear
 * stretch/framerate correction) because that's exactly what [SUBTITLE_DELAY_MIN_MS]..
 * [SUBTITLE_DELAY_MAX_MS] / setSubtitleDelay() already applies — no point computing a
 * correction the rest of the app has no way to apply.
 *
 * Performance note: align() is a straightforward O(frameCount x offsetRange) scan, not
 * an FFT-based correlation. That's deliberate simplicity, not an oversight -- but it
 * means the caller MUST NOT hand this a full movie's worth of signal. It's sized for
 * AudioVoiceActivityProvider sampling a handful of short windows (a few minutes of
 * audio total across the file), not decoding the whole thing -- see that interface's
 * doc. With ~5 minutes of 20ms-frame signal and a +/-60s search range that's ~15k
 * frames x ~6k offsets ~= 90M boolean comparisons, fine for a one-shot "syncing..."
 * action; the same call against 2 hours of audio would be ~30x that and is not this
 * design's job. If real-device profiling ever shows this loop itself is the bottleneck
 * within its intended input size, a bitset+popcount or FFT-based correlation is the
 * natural next step -- not attempted here since it can't be benchmarked without a real
 * device/build.
 */
object SubtitleAudioAligner {

    /** Frame size for both binary signals. 20ms is generous relative to typical VAD
     * hangover/attack times and keeps the correlation loop cheap even for a 2+ hour movie. */
    private const val FRAME_MS = 20L

    fun align(
        cues: List<SubtitleAlignmentCue>,
        voiceActivity: List<VoiceActivitySample>,
        searchRangeMs: LongRange = SUBTITLE_DELAY_MIN_MS.toLong()..SUBTITLE_DELAY_MAX_MS.toLong(),
        frameMs: Long = FRAME_MS,
    ): SubtitleAutoAlignmentResult? {
        if (cues.isEmpty() || voiceActivity.isEmpty()) return null

        val timelineEndMs = max(
            cues.maxOf { it.endMs },
            voiceActivity.maxOf { it.endMs },
        ) + max(0L, searchRangeMs.last)
        val frameCount = (timelineEndMs / frameMs + 1).toInt()
        if (frameCount <= 0) return null

        val subtitleSignal = rasterize(cues.map { it.startMs to it.endMs }, frameMs, frameCount)
        val voiceSignal = rasterize(voiceActivity.map { it.startMs to it.endMs }, frameMs, frameCount)

        val subtitleActiveFrames = subtitleSignal.count { it }
        if (subtitleActiveFrames == 0) return null

        val minOffsetFrames = (searchRangeMs.first / frameMs).toInt()
        val maxOffsetFrames = (searchRangeMs.last / frameMs).toInt()
        if (minOffsetFrames > maxOffsetFrames) return null

        var bestOffsetFrames = 0
        var bestScore = -1L
        for (offsetFrames in minOffsetFrames..maxOffsetFrames) {
            val score = overlapScore(subtitleSignal, voiceSignal, offsetFrames)
            if (score > bestScore) {
                bestScore = score
                bestOffsetFrames = offsetFrames
            }
        }

        // Confidence: how much of the subtitle-active time actually landed on detected
        // speech at the winning offset, vs. the theoretical max (every subtitle-active
        // frame overlapping speech). 1.0 = perfect overlap, ~0 = essentially no signal.
        val confidence = bestScore.toDouble() / subtitleActiveFrames.toDouble()

        // Offset sign convention matches setSubtitleDelay(): a positive delay pushes
        // subtitles LATER. If voice activity happens `shift` frames after where the
        // subtitle-signal said it should, subtitles are early and need to be delayed
        // by that same amount, so offsetMs == -(frame shift used to align them) is
        // *not* right here — overlapScore already searches directly in "delay to
        // apply" space (see its doc), so bestOffsetFrames is used as-is.
        val offsetMs = (bestOffsetFrames * frameMs).toInt()
            .coerceIn(searchRangeMs.first.toInt(), searchRangeMs.last.toInt())

        return SubtitleAutoAlignmentResult(offsetMs = offsetMs, confidence = confidence)
    }

    /** True/false-per-frame signal: true while any interval in [intervals] covers that frame. */
    private fun rasterize(intervals: List<Pair<Long, Long>>, frameMs: Long, frameCount: Int): BooleanArray {
        val signal = BooleanArray(frameCount)
        for ((startMs, endMs) in intervals) {
            val startFrame = (startMs / frameMs).toInt().coerceIn(0, frameCount)
            val endFrame = (endMs / frameMs).toInt().coerceIn(0, frameCount)
            for (frame in startFrame until endFrame) signal[frame] = true
        }
        return signal
    }

    /**
     * Number of frames where subtitleSignal[i] and voiceSignal[i + delayFrames] are both
     * true — i.e. "if we delayed the subtitles by delayFrames, how many subtitle-active
     * frames would land on detected speech". Matches setSubtitleDelay's sign convention
     * directly: delaying subtitles by +N frames means comparing subtitle frame i against
     * voice frame i + N (the subtitle that used to show at i now effectively shows at i,
     * but the *voice* it should match already happened N frames earlier at i, so we look
     * ahead by N in the voice signal to find where that speech actually was).
     */
    private fun overlapScore(subtitleSignal: BooleanArray, voiceSignal: BooleanArray, delayFrames: Int): Long {
        var score = 0L
        val size = subtitleSignal.size
        val lo = max(0, -delayFrames)
        val hi = min(size, size - delayFrames)
        if (lo >= hi) return 0L
        for (i in lo until hi) {
            if (subtitleSignal[i] && voiceSignal[i + delayFrames]) score++
        }
        return score
    }
}
