package com.nuvio.app.core.transcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscodeLadderTest {
    private val url = "http://h:8889/proxy/transcode/playlist.m3u8?api_password=pw&bitrate=5000k&d=https%3A%2F%2Fx"

    @Test fun selectsHighestTierWithHeadroom() {
        assertEquals("Max", TranscodeLadder.selectTier(40.0).name)
        assertEquals("Very High", TranscodeLadder.selectTier(10.0).name) // 6.5 usable -> 5.19 fits, 8.19 no
        assertEquals("Ultra", TranscodeLadder.selectTier(13.0).name)
        assertEquals("Balanced", TranscodeLadder.selectTier(3.5).name)
        assertEquals("Low", TranscodeLadder.selectTier(0.5).name)
    }

    @Test fun unknownSpeedUsesDefault() {
        assertEquals(TranscodeLadder.defaultStartTier, TranscodeLadder.selectTier(null))
    }

    @Test fun urlHelpers() {
        assertEquals(5000, TranscodeUrls.tierOf(url)?.videoKbps)
        assertEquals("http://h:8889", TranscodeUrls.origin(url))
        val low = TranscodeUrls.withTier(url, TranscodeLadder.tiers.first())
        assertEquals("http://h:8889/proxy/transcode/playlist.m3u8?api_password=pw&d=https%3A%2F%2Fx&bitrate=750k", low)
        val master = TranscodeUrls.asMaster(url)
        assertEquals("http://h:8889/proxy/transcode/master.m3u8?api_password=pw&d=https%3A%2F%2Fx", master)
        assertEquals(750, TranscodeUrls.tierOf(TranscodeUrls.withTier(master, TranscodeLadder.tiers.first()))?.videoKbps)
        assertEquals("http://h:8889/proxy/speedtest?bytes=1000&api_password=pw", TranscodeUrls.speedtestUrl(url, 1000))
        assertNull(TranscodeUrls.origin("https://example.com/video.m3u8"))
    }
}

class AdaptiveQualityControllerTest {
    private val vh = TranscodeLadder.tiers[3] // Very High 5000k, total 5.192

    private fun ctl(tier: TranscodeTier = vh) = AdaptiveQualityController(tier, startedAtMs = 0)
    private fun sample(t: Long, buf: Long? = 30_000, probe: Double? = null, playing: Boolean = true, loading: Boolean = false) =
        AdaptiveQualityController.Sample(t, playing, loading, buf, probe)

    @Test fun noDecisionsDuringWarmup() {
        val c = ctl()
        assertNull(c.onSample(sample(5_000, buf = 0, probe = 0.1)))
        assertNull(c.onSample(sample(29_000, buf = 0, probe = 0.1)))
    }

    @Test fun dropsWhenBufferDrains() {
        val c = ctl()
        assertNull(c.onSample(sample(31_000, buf = 2_000)))
        assertNull(c.onSample(sample(33_000, buf = 1_000)))
        assertEquals(TranscodeLadder.tiers[2], c.onSample(sample(35_000, buf = 500)))
    }

    @Test fun dropsToTierThroughputSupportsInOneStep() {
        val c = ctl()
        // probe 2.5 Mbps: VH 5.19 > 2.0, so drop; supported tier at 0.8 budget = Balanced (1.99 <= 2.0)
        assertEquals(TranscodeLadder.tiers[1], c.onSample(sample(31_000, probe = 2.5)))
    }

    @Test fun pausedDoesNothing() {
        val c = ctl()
        repeat(5) { assertNull(c.onSample(sample(31_000L + it * 2000, buf = 0, playing = false))) }
    }

    @Test fun upRequiresConfirmationsAndCooldown() {
        val c = ctl(TranscodeLadder.tiers[2]) // High
        assertNull(c.onSample(sample(31_000, probe = 20.0)))
        assertNull(c.onSample(sample(33_000, probe = 20.0)))
        // third confirmation but cooldown (60 s since start) not yet elapsed
        assertNull(c.onSample(sample(35_000, probe = 20.0)))
        assertEquals(TranscodeLadder.tiers[3], c.onSample(sample(61_000, probe = 20.0)))
    }

    @Test fun upIsOneStepOnly() {
        val c = ctl(TranscodeLadder.tiers[0])
        repeat(2) { c.onSample(sample(31_000L + it * 2000, probe = 50.0)) }
        assertEquals(TranscodeLadder.tiers[1], c.onSample(sample(61_000, probe = 50.0)))
    }

    @Test fun noUpWhenBufferThin() {
        val c = ctl(TranscodeLadder.tiers[2])
        repeat(10) { assertNull(c.onSample(sample(61_000L + it * 2000, buf = 8_000, probe = 50.0))) }
    }

    @Test fun noPingPongAfterFailure() {
        val c = ctl()
        // drain -> down to High
        c.onSample(sample(31_000, buf = 1000)); c.onSample(sample(33_000, buf = 1000))
        assertEquals(TranscodeLadder.tiers[2], c.onSample(sample(35_000, buf = 1000)))
        // fast probes right away: still blocked by post-down cooldown (120 s) and failed-tier block
        var t = 60_000L
        repeat(10) { assertNull(c.onSample(sample(t, probe = 30.0))); t += 2_000 }
        // even after the cooldown the failed tier stays blocked for 5 minutes
        assertNull(c.onSample(sample(200_000, probe = 30.0)))
        assertNull(c.onSample(sample(202_000, probe = 30.0)))
        assertNull(c.onSample(sample(204_000, probe = 30.0)))
        // after the block expires it may go up again
        c.onSample(sample(340_000, probe = 30.0)); c.onSample(sample(342_000, probe = 30.0))
        assertEquals(vh, c.onSample(sample(344_000, probe = 30.0)))
    }

    @Test fun neverBelowLowest() {
        val c = ctl(TranscodeLadder.tiers[0])
        repeat(5) { assertNull(c.onSample(sample(31_000L + it * 2000, buf = 0))) }
    }
}
