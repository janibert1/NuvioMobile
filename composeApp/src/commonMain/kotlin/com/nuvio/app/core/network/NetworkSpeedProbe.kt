package com.nuvio.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

object NetworkSpeedProbe {
    private val mutex = Mutex()
    private var lastSpeedMbps: Double? = null
    private var lastProbeTimestampMs: Long = 0L
    private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour

    // Fast 5MB test payload
    private const val PROBE_URL = "https://speed.cloudflare.com/__down?bytes=5000000"

    suspend fun getOrMeasureSpeedMbps(client: HttpClient = createApiHttpClient()): Double? {
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            if (lastSpeedMbps != null && (now - lastProbeTimestampMs) < CACHE_TTL_MS) {
                return lastSpeedMbps
            }
        }
        return measureSpeedMbps(client)
    }

    suspend fun measureSpeedMbps(client: HttpClient = createApiHttpClient()): Double? = withContext(Dispatchers.Default) {
        runCatching {
            val startTime = Clock.System.now().toEpochMilliseconds()
            val response = client.get(PROBE_URL)
            val bytes = response.readRawBytes()
            val endTime = Clock.System.now().toEpochMilliseconds()
            val durationMs = (endTime - startTime).coerceAtLeast(1L)

            val durationSec = durationMs / 1000.0
            val bits = bytes.size * 8.0
            val mbps = bits / (durationSec * 1_000_000.0)

            mutex.withLock {
                lastSpeedMbps = mbps
                lastProbeTimestampMs = Clock.System.now().toEpochMilliseconds()
            }
            mbps
        }.getOrNull()
    }

    fun cachedSpeedMbps(): Double? = lastSpeedMbps
}
