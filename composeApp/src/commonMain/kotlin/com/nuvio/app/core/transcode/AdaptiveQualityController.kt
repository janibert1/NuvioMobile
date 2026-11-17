package com.nuvio.app.core.transcode

/**
 * Pure decision logic for app-side adaptive bitrate on players without native HLS ABR (iOS/MPV).
 * Feed it one [Sample] every ~2 s while playing; it answers with the tier to switch to, or null.
 *
 * Rules:
 *  - nothing happens during the first [WARMUP_MS] after playback (re)start;
 *  - DOWN when buffer-ahead stays under [LOW_BUFFER_MS] for [LOW_BUFFER_SAMPLES] samples, or the probed
 *    throughput can no longer carry the current tier (with [DOWN_BUDGET] of it); drop straight to the tier
 *    the throughput supports (at least one step);
 *  - UP one step at a time, only after [UP_CONFIRMATIONS] consecutive probes support the next tier at
 *    [UP_BUDGET] (stricter than the downward budget = hysteresis) and buffer is healthy;
 *  - cooldowns after every switch, and a tier that just failed is blocked for [FAILED_TIER_BLOCK_MS].
 */
class AdaptiveQualityController(
    startTier: TranscodeTier,
    startedAtMs: Long,
    private val maxTier: TranscodeTier = TranscodeLadder.tiers.last(),
) {
    data class Sample(
        val nowMs: Long,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        /** buffered position - playback position, null if unknown. */
        val bufferAheadMs: Long?,
        /** Latest speedtest result (phone -> proxy), null if no fresh probe this tick. */
        val probeMbps: Double? = null,
    )

    var currentTier: TranscodeTier = startTier
        private set

    private var lastSwitchAtMs = startedAtMs
    private var lastSwitchWasDown = false
    private var startedAt = startedAtMs
    private var lowBufferStreak = 0
    private var upStreak = 0
    private var lastProbeMbps: Double? = null
    private var blockedTier: TranscodeTier? = null
    private var blockedUntilMs = 0L

    /** Call when the user picks a tier manually / source reloaded for another reason. */
    fun reset(tier: TranscodeTier, nowMs: Long) {
        currentTier = tier
        startedAt = nowMs
        lastSwitchAtMs = nowMs
        lowBufferStreak = 0
        upStreak = 0
        blockedTier = null
    }

    fun onSample(s: Sample): TranscodeTier? {
        s.probeMbps?.let { lastProbeMbps = it }
        if (s.nowMs - startedAt < WARMUP_MS) return null
        if (!s.isPlaying && !s.isLoading) return null // paused: neither drain nor headroom information

        val buffer = s.bufferAheadMs
        val starving = s.isLoading || (buffer != null && buffer < LOW_BUFFER_MS)
        lowBufferStreak = if (starving) lowBufferStreak + 1 else 0

        val sinceSwitch = s.nowMs - lastSwitchAtMs
        val probe = s.probeMbps

        // ---- down ----
        val bufferDrain = lowBufferStreak >= LOW_BUFFER_SAMPLES
        val probeTooSlow = probe != null && currentTier.totalMbps > probe * DOWN_BUDGET
        if ((bufferDrain || probeTooSlow) && sinceSwitch >= DOWN_COOLDOWN_MS) {
            val lower = TranscodeLadder.lower(currentTier)
            if (lower != null) {
                val supported = lastProbeMbps?.let { TranscodeLadder.selectTier(it, DOWN_BUDGET) }
                val target = if (supported != null && TranscodeLadder.indexOf(supported) < TranscodeLadder.indexOf(lower)) supported else lower
                return switchTo(target, s.nowMs, down = true, failed = currentTier)
            }
        }

        // ---- up ----
        if (probe == null) return null
        val next = TranscodeLadder.higher(currentTier)
        val healthy = !starving && (buffer == null || buffer >= UP_MIN_BUFFER_MS)
        val canUp = next != null &&
            TranscodeLadder.indexOf(next) <= TranscodeLadder.indexOf(maxTier) &&
            next.totalMbps <= probe * UP_BUDGET &&
            healthy &&
            !(blockedTier?.videoKbps == next.videoKbps && s.nowMs < blockedUntilMs)
        upStreak = if (canUp) upStreak + 1 else 0
        val upCooldown = if (lastSwitchWasDown) UP_AFTER_DOWN_COOLDOWN_MS else UP_COOLDOWN_MS
        if (upStreak >= UP_CONFIRMATIONS && sinceSwitch >= upCooldown && next != null) {
            return switchTo(next, s.nowMs, down = false, failed = null)
        }
        return null
    }

    private fun switchTo(tier: TranscodeTier, nowMs: Long, down: Boolean, failed: TranscodeTier?): TranscodeTier {
        if (failed != null) {
            blockedTier = failed
            blockedUntilMs = nowMs + FAILED_TIER_BLOCK_MS
        }
        currentTier = tier
        lastSwitchAtMs = nowMs
        lastSwitchWasDown = down
        // The reload resets the player's buffer; give it a fresh warmup before judging again.
        startedAt = nowMs - WARMUP_MS + POST_SWITCH_WARMUP_MS
        lowBufferStreak = 0
        upStreak = 0
        return tier
    }

    companion object {
        const val WARMUP_MS = 30_000L
        const val POST_SWITCH_WARMUP_MS = 20_000L
        const val LOW_BUFFER_MS = 6_000L
        const val LOW_BUFFER_SAMPLES = 3
        const val UP_MIN_BUFFER_MS = 15_000L
        const val DOWN_BUDGET = 0.80
        const val UP_BUDGET = 0.60
        const val UP_CONFIRMATIONS = 3
        const val DOWN_COOLDOWN_MS = 15_000L
        const val UP_COOLDOWN_MS = 60_000L
        const val UP_AFTER_DOWN_COOLDOWN_MS = 120_000L
        const val FAILED_TIER_BLOCK_MS = 300_000L
    }
}
