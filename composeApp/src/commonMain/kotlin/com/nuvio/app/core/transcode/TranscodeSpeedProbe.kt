package com.nuvio.app.core.transcode

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Measures the downlink between the phone and the transcode proxy itself (the path that actually matters)
 * via `GET /proxy/speedtest?bytes=N`. Results are cached per proxy origin.
 */
object TranscodeSpeedProbe {
    private val mutex = Mutex()
    private val speedByOrigin = HashMap<String, Double>()
    private var lastSpeedMbps: Double? = null

    private const val STARTUP_BYTES = 2_000_000
    private const val LIVE_BYTES = 1_000_000
    private const val TIMEOUT_MS = 12_000L

    /** Most recent measurement for [url]'s proxy, or the last one for any proxy. */
    fun cachedSpeedMbps(url: String? = null): Double? {
        val origin = url?.let(TranscodeUrls::origin)
        return (origin?.let { speedByOrigin[it] }) ?: lastSpeedMbps
    }

    /** Larger payload; used before choosing the starting tier. */
    suspend fun measureStartup(url: String, client: HttpClient = createApiHttpClient()): Double? =
        measure(url, STARTUP_BYTES, client)

    /** Smaller payload; used periodically during playback. */
    suspend fun measureLive(url: String, client: HttpClient = createApiHttpClient()): Double? =
        measure(url, LIVE_BYTES, client)

    private suspend fun measure(url: String, bytes: Int, client: HttpClient): Double? {
        val probeUrl = TranscodeUrls.speedtestUrl(url, bytes) ?: return null
        val origin = TranscodeUrls.origin(url) ?: return null
        val mbps = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    val start = Clock.System.now().toEpochMilliseconds()
                    val received = client.get(probeUrl).readRawBytes().size
                    val ms = (Clock.System.now().toEpochMilliseconds() - start).coerceAtLeast(1L)
                    if (received < bytes / 2) null else received * 8.0 / (ms / 1000.0 * 1_000_000.0)
                }.getOrNull()
            }
        } ?: return null
        mutex.withLock {
            speedByOrigin[origin] = mbps
            lastSpeedMbps = mbps
        }
        return mbps
    }
}
