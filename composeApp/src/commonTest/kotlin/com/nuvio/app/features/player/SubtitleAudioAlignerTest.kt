package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleAudioAlignerTest {

    /** A handful of dialogue-shaped cues, spread across a ~90s window — deliberately
     * irregular gaps/durations so the correlation has real structure to lock onto
     * (a single repeating pulse train would have many equally-good offsets). */
    private val baseCues = listOf(
        SubtitleAlignmentCue(1_000, 2_400),
        SubtitleAlignmentCue(3_000, 3_800),
        SubtitleAlignmentCue(7_500, 9_200),
        SubtitleAlignmentCue(15_000, 15_600),
        SubtitleAlignmentCue(22_300, 24_100),
        SubtitleAlignmentCue(31_000, 31_900),
        SubtitleAlignmentCue(40_200, 42_000),
        SubtitleAlignmentCue(55_000, 56_500),
        SubtitleAlignmentCue(70_100, 71_400),
        SubtitleAlignmentCue(85_000, 86_800),
    )

    private fun shifted(cues: List<SubtitleAlignmentCue>, byMs: Long): List<VoiceActivitySample> =
        cues.map { VoiceActivitySample((it.startMs + byMs).coerceAtLeast(0), it.endMs + byMs) }

    @Test
    fun recoversAPositiveShift_subtitlesAreEarly_needDelaying() {
        // Speech actually happens 2500ms LATER than where the subtitle cues claim ->
        // subtitles are early -> correct fix is to delay them by +2500ms.
        val voiceActivity = shifted(baseCues, byMs = 2_500)

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, voiceActivity), "expected a result")

        assertEquals(2_500, result.offsetMs)
        assertTrue(result.confidence > 0.9, "expected near-perfect confidence, got ${result.confidence}")
    }

    @Test
    fun recoversANegativeShift_subtitlesAreLate_needAdvancing() {
        // Speech happens 1800ms EARLIER than the cues claim -> subtitles are late ->
        // correct fix is a negative delay (show them earlier).
        val voiceActivity = shifted(baseCues, byMs = -1_800)

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, voiceActivity), "expected a result")

        assertEquals(-1_800, result.offsetMs)
        assertTrue(result.confidence > 0.9, "expected near-perfect confidence, got ${result.confidence}")
    }

    @Test
    fun zeroShiftWhenAlreadyInSync() {
        val voiceActivity = shifted(baseCues, byMs = 0)

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, voiceActivity))

        assertEquals(0, result.offsetMs)
        assertTrue(result.confidence > 0.9)
    }

    @Test
    fun quantizesToTheNearestFrame() {
        // 2_513ms isn't a multiple of the 20ms frame size -- the recovered offset should
        // land on the nearest frame boundary below it (2_500ms), not fail outright.
        val voiceActivity = shifted(baseCues, byMs = 2_513)

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, voiceActivity))

        assertTrue(kotlin.math.abs(result.offsetMs - 2_513) <= 20, "offset ${result.offsetMs} not within one frame of 2513")
    }

    @Test
    fun lowConfidenceWhenVoiceActivityIsUnrelatedNoise() {
        // Voice activity with no real relationship to the cue timing at all -- some
        // offset will still score best (it's a search over a finite range), but
        // confidence should be low, which is exactly the signal callers need to avoid
        // trusting a bogus offset.
        val unrelatedVoiceActivity = listOf(
            VoiceActivitySample(500, 600),
            VoiceActivitySample(12_345, 12_400),
            VoiceActivitySample(50_000, 50_050),
        )

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, unrelatedVoiceActivity))

        assertTrue(result.confidence < SUBTITLE_AUTO_SYNC_MIN_CONFIDENCE, "expected low confidence, got ${result.confidence}")
    }

    @Test
    fun returnsNullOnEmptyInput() {
        assertNull(SubtitleAudioAligner.align(emptyList(), shifted(baseCues, 0)))
        assertNull(SubtitleAudioAligner.align(baseCues, emptyList()))
    }

    @Test
    fun respectsSearchRangeBounds() {
        // Real shift is +10s, but the caller only allows searching up to +3s -- the
        // result must stay within range (and correspondingly have poor confidence),
        // never silently exceed the range it was asked to search.
        val voiceActivity = shifted(baseCues, byMs = 10_000)

        val result = assertNotNull(SubtitleAudioAligner.align(baseCues, voiceActivity, searchRangeMs = -3_000L..3_000L))

        assertTrue(result.offsetMs in -3_000..3_000, "offset ${result.offsetMs} escaped the requested search range")
    }
}
