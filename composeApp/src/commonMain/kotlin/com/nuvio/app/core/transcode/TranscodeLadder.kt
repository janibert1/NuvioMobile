package com.nuvio.app.core.transcode

/**
 * Bitrate ladder of the transcode proxy (fedora mediaflow add-on). One tier == one
 * `/proxy/transcode/playlist.m3u8?bitrate=<kbps>k` playlist; `master.m3u8` lists them all.
 */
data class TranscodeTier(
    val name: String,
    val videoKbps: Int,
) {
    /** Bandwidth the player needs: video + ~192k audio. */
    val totalMbps: Double get() = (videoKbps + AUDIO_KBPS) / 1000.0
    val label: String get() = "$name · ${formatMbps(videoKbps / 1000.0)} Mbps"

    companion object {
        const val AUDIO_KBPS = 192
    }
}

internal fun formatMbps(value: Double): String {
    val tenths = (value * 10).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

object TranscodeLadder {
    /** Lowest -> highest. */
    val tiers: List<TranscodeTier> = listOf(
        TranscodeTier("Low", 750),
        TranscodeTier("Balanced", 1800),
        TranscodeTier("High", 3200),
        TranscodeTier("Very High", 5000),
        TranscodeTier("Ultra", 8000),
        TranscodeTier("Max", 10000),
    )

    /** Fraction of measured throughput the stream may consume (30-40% headroom). */
    const val STARTUP_BUDGET = 0.65

    /** Tier used when nothing has been measured yet. */
    val defaultStartTier: TranscodeTier get() = tiers[3]
    val cellularStartTier: TranscodeTier get() = tiers[1]

    fun tierForKbps(kbps: Int): TranscodeTier? = tiers.firstOrNull { it.videoKbps == kbps }

    fun indexOf(tier: TranscodeTier): Int = tiers.indexOfFirst { it.videoKbps == tier.videoKbps }

    fun lower(tier: TranscodeTier): TranscodeTier? = tiers.getOrNull(indexOf(tier) - 1)

    fun higher(tier: TranscodeTier): TranscodeTier? = tiers.getOrNull(indexOf(tier) + 1)

    /** Highest tier whose total bitrate fits inside [budget] of [speedMbps]; lowest tier if none do. */
    fun selectTier(speedMbps: Double?, budget: Double = STARTUP_BUDGET): TranscodeTier {
        if (speedMbps == null || speedMbps.isNaN() || speedMbps <= 0.0) return defaultStartTier
        val usable = speedMbps * budget
        return tiers.lastOrNull { it.totalMbps <= usable } ?: tiers.first()
    }
}

/** URL helpers for the proxy's transcode endpoints. */
object TranscodeUrls {
    private const val PLAYLIST_PATH = "/proxy/transcode/playlist.m3u8"
    private const val MASTER_PATH = "/proxy/transcode/master.m3u8"

    fun isTierPlaylist(url: String): Boolean = url.substringBefore('?').endsWith(PLAYLIST_PATH)

    fun isMaster(url: String): Boolean = url.substringBefore('?').endsWith(MASTER_PATH)

    fun isTranscode(url: String): Boolean = isTierPlaylist(url) || isMaster(url)

    /** `scheme://host:port` of the proxy, or null if [url] is not a transcode URL. */
    fun origin(url: String): String? {
        if (!isTranscode(url)) return null
        val path = if (isMaster(url)) MASTER_PATH else PLAYLIST_PATH
        return url.substringBefore('?').removeSuffix(path).takeIf { it.isNotBlank() }
    }

    fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    /** Tier encoded in a per-bitrate playlist URL (`bitrate=5000k`). */
    fun tierOf(url: String): TranscodeTier? {
        if (!isTierPlaylist(url)) return null
        val kbps = queryParam(url, "bitrate")?.removeSuffix("k")?.toIntOrNull() ?: return null
        return TranscodeLadder.tierForKbps(kbps)
    }

    /** Per-bitrate playlist URL for [tier], derived from either a tier playlist or master URL. */
    fun withTier(url: String, tier: TranscodeTier): String {
        val origin = origin(url) ?: return url
        val query = url.substringAfter('?', "")
        val params = query.split('&').filter { it.isNotEmpty() }
            .filterNot { it.startsWith("bitrate=") }
        return buildString {
            append(origin).append(PLAYLIST_PATH).append('?')
            append((params + "bitrate=${tier.videoKbps}k").joinToString("&"))
        }
    }

    /** Adaptive master playlist URL derived from a tier playlist URL (used where the player does native ABR). */
    fun asMaster(url: String): String {
        if (isMaster(url)) return url
        val origin = origin(url) ?: return url
        val params = url.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && !it.startsWith("bitrate=") }
        return "$origin$MASTER_PATH?${params.joinToString("&")}"
    }

    /** URL of the speedtest endpoint on the same proxy (reuses the api_password). */
    fun speedtestUrl(url: String, bytes: Int): String? {
        val origin = origin(url) ?: return null
        val pw = queryParam(url, "api_password")
        val auth = if (pw != null) "&api_password=$pw" else ""
        return "$origin/proxy/speedtest?bytes=$bytes$auth"
    }
}
