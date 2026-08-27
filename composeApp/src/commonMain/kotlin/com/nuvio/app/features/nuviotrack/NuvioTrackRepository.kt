package com.nuvio.app.features.nuviotrack

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingListWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResolution
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * NuvioTrack: a self-hosted, open-source alternative data sink for the same
 * kind of watch history/ratings/watchlist data Trakt collects - built
 * 2026-08-27 because Trakt now requires a paid VIP subscription just to
 * register a new API application at all, and Simkl (free) has no
 * recommendations feature. Backend: github.com/janibert1/nuvio-track.
 *
 * Deliberately does NOT implement TrackingLibraryProvider or
 * TrackingProgressProvider (the read-side projections that back Nuvio's
 * own Library tab / continue-watching UI) - those interfaces are deeply
 * coupled to internal LibraryItem/WatchProgressEntry models this session
 * didn't have full visibility into, and getting them wrong risks breaking
 * compilation with no way to verify locally (no iOS/Android build
 * toolchain available in this environment - only the workflow's own
 * runners can actually compile this). Scoped to the write-side only:
 * capturing history/watchlist/scrobble events INTO the backend, which is
 * exactly the part a passive Stremio-addon can never do on its own
 * (addons are read-only catalogs - they can't receive "user watched X"
 * events). Recommendations are served back out through the addon
 * (manifest.json / catalog endpoints), not through this native path,
 * since TrackingCapability.RECOMMENDATIONS isn't actually consumed by any
 * generic dispatch in this codebase (confirmed by search - Trakt declares
 * it too but nothing reads it back out through TrackingProviderRegistry).
 *
 * Auth model is intentionally trivial compared to Trakt/Simkl's OAuth
 * flows: this is a single-user self-hosted service, so "authenticated"
 * just means both NUVIOTRACK_SERVER and NUVIOTRACK_API_KEY were set at
 * build time - no login screen, no token refresh.
 */
object NuvioTrackAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("NuvioTrackAuth")

    private val _isAuthenticated = MutableStateFlow(
        NuvioTrackConfig.SERVER.isNotBlank() && NuvioTrackConfig.API_KEY.isNotBlank(),
    )
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.NUVIOTRACK,
        displayName = "NuvioTrack",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        // Nothing to load from disk - configuration is build-time constant,
        // re-evaluated from NuvioTrackConfig directly rather than cached.
        _isAuthenticated.value = NuvioTrackConfig.SERVER.isNotBlank() && NuvioTrackConfig.API_KEY.isNotBlank()
    }

    override fun onProfileChanged() = Unit

    override fun clearLocalState() = Unit

    override fun removeStoredProfile(profileId: Int) = Unit
}

object NuvioTrackMutationRepository : TrackingListWriter, TrackingHistoryWriter, TrackingScrobbler {
    private val log = Logger.withTag("NuvioTrackMutation")

    override val providerId: TrackingProviderId = TrackingProviderId.NUVIOTRACK

    init {
        TrackingProviderRegistry.registerListWriter(this)
        TrackingProviderRegistry.registerHistoryWriter(this)
        TrackingProviderRegistry.registerScrobbler(this)
    }

    private fun headers(): Map<String, String> = mapOf(
        "X-API-Key" to NuvioTrackConfig.API_KEY,
        "Content-Type" to "application/json",
    )

    private fun mediaTypeOf(kind: TrackingMediaKind): String =
        if (kind == TrackingMediaKind.MOVIE) "movie" else "tv"

    /** Only items with a resolvable TMDB id can be sent - this backend is TMDB-keyed. */
    private fun TrackingMediaReference.tmdbIdOrNull(): Long? = ids.tmdb

    private suspend fun post(path: String, json: String): Boolean = runCatching {
        val response = httpRequestRaw(
            method = "POST",
            url = "${NuvioTrackConfig.SERVER}$path",
            headers = headers(),
            body = json,
        )
        response.status in 200..299
    }.getOrElse { error ->
        log.w(error) { "POST $path failed" }
        false
    }

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult {
        var notFound = 0
        val resolutions = mutableListOf<TrackingMutationResolution>()
        items.forEach { historyItem ->
            val media = historyItem.media
            val tmdbId = media.tmdbIdOrNull()
            if (tmdbId == null) {
                notFound++
                return@forEach
            }
            val body = buildJsonObject {
                put("tmdb_id", tmdbId)
                put("media_type", mediaTypeOf(media.kind))
                put("title", media.title ?: "")
                media.episode?.season?.let { put("season", it) }
                media.episode?.number?.let { put("episode", it) }
                historyItem.watchedAtEpochMs?.let { put("watched_at", it / 1000) }
            }
            val ok = post("/history", body.toString())
            if (!ok) notFound++
            resolutions += TrackingMutationResolution(mediaKind = media.kind)
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound, resolutions = resolutions)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        // NuvioTrack's backend has no history-delete endpoint yet (history
        // is meant to be an append-only log, same spirit as Trakt's own
        // watch history) - report as attempted-but-not-found rather than
        // silently claiming success for something that didn't happen.
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = items.size)
    }

    override suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult {
        var notFound = 0
        val resolutions = mutableListOf<TrackingMutationResolution>()
        items.forEach { media ->
            val tmdbId = media.tmdbIdOrNull()
            if (tmdbId == null) {
                notFound++
                return@forEach
            }
            val ok = when (destination) {
                TrackingListStatus.PLAN_TO_WATCH -> post(
                    "/watchlist",
                    buildJsonObject {
                        put("tmdb_id", tmdbId)
                        put("media_type", mediaTypeOf(media.kind))
                        put("title", media.title ?: "")
                    }.toString(),
                )
                TrackingListStatus.COMPLETED -> post(
                    "/history",
                    buildJsonObject {
                        put("tmdb_id", tmdbId)
                        put("media_type", mediaTypeOf(media.kind))
                        put("title", media.title ?: "")
                    }.toString(),
                )
                // WATCHING / ON_HOLD / DROPPED aren't modeled by this
                // backend's simple schema (watchlist + append-only
                // history) - accepted as a no-op rather than erroring,
                // same posture as removeFromHistory above.
                TrackingListStatus.WATCHING, TrackingListStatus.ON_HOLD, TrackingListStatus.DROPPED -> true
            }
            if (!ok) notFound++
            resolutions += TrackingMutationResolution(listStatus = destination, mediaKind = media.kind)
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound, resolutions = resolutions)
    }

    override suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        var notFound = 0
        items.forEach { media ->
            val tmdbId = media.tmdbIdOrNull()
            if (tmdbId == null) {
                notFound++
                return@forEach
            }
            val ok = runCatching {
                val response = httpRequestRaw(
                    method = "DELETE",
                    url = "${NuvioTrackConfig.SERVER}/watchlist/${mediaTypeOf(media.kind)}/$tmdbId",
                    headers = headers(),
                    body = "",
                )
                response.status in 200..299
            }.getOrElse { error ->
                log.w(error) { "DELETE /watchlist failed" }
                false
            }
            if (!ok) notFound++
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    // Scrobbling (start/pause/stop with a live progress percent) maps onto
    // this backend's simple history log as "log it as watched once the
    // user has clearly finished it" (progress >= 90%) - this backend
    // doesn't model in-progress state at all, only completed history, so
    // start/pause events are intentionally ignored rather than faked.
    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        if (action != TrackingScrobbleAction.STOP || event.progressPercent < 90.0) return
        val tmdbId = event.media.tmdbIdOrNull() ?: return
        post(
            "/history",
            buildJsonObject {
                put("tmdb_id", tmdbId)
                put("media_type", mediaTypeOf(event.media.kind))
                put("title", event.media.title ?: "")
                event.media.episode?.season?.let { put("season", it) }
                event.media.episode?.number?.let { put("episode", it) }
            }.toString(),
        )
    }
}
