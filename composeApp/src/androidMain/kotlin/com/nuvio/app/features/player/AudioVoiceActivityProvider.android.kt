package com.nuvio.app.features.player

/**
 * TODO(subtitle-auto-sync): not implemented. Needs MediaExtractor + MediaCodec to decode
 * [windows] of [mediaUrl]'s audio track to PCM, then a VAD pass over each — see the
 * expect declaration's doc for the intended approach. Returns empty (no detected
 * activity) rather than throwing, which SubtitleAudioAligner.align() already treats as
 * "nothing to align against" (align() short-circuits on an empty list) — so wiring this
 * up now is inert-but-safe, not a crash risk, until the real decode lands.
 */
actual object AudioVoiceActivityProvider {
    actual suspend fun detectVoiceActivity(
        mediaUrl: String,
        headers: Map<String, String>,
        windows: List<LongRange>,
    ): List<VoiceActivitySample> = emptyList()
}
