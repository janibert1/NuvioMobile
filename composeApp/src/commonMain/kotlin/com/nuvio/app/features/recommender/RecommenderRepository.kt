package com.nuvio.app.features.recommender

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges the on-device [NuvioRecommender] to the rest of the app: turns
 * local watch history into a recommendation, then resolves each
 * recommended TMDB id into a displayable [MetaPreview] via the app's
 * existing TMDB metadata service (same one the detail screen already
 * uses — see TmdbMetadataService.fetchStandaloneMeta).
 *
 * A plain `object` singleton, matching this codebase's existing pattern
 * for this class of repository (see TmdbSettingsRepository,
 * WatchProgressRepository) rather than introducing a DI framework.
 */
object RecommenderRepository {
    private val loadMutex = Mutex()
    private var recommender: NuvioRecommender? = null
    private var loadFailed = false

    private suspend fun ensureLoaded(): NuvioRecommender? {
        recommender?.let { return it }
        if (loadFailed) return null
        loadMutex.withLock {
            recommender?.let { return it }
            if (loadFailed) return null
            return try {
                NuvioRecommender.load().also { recommender = it }
            } catch (t: Throwable) {
                Logger.w("RecommenderRepository") { "failed to load on-device recommender: ${t.message}" }
                loadFailed = true
                null
            }
        }
    }

    /**
     * Parses a "tmdb:<id>" style content id (see TmdbMetadataService's own
     * id convention) into a raw TMDB integer id. Entries sourced from a
     * non-TMDB addon (a different id scheme entirely) return null and are
     * simply excluded from the history used to personalize — not an error,
     * just data the on-device catalog has no way to match.
     */
    private fun WatchProgressEntry.tmdbId(): Int? =
        parentMetaId.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()

    /**
     * Recommended [MetaPreview]s for the current watch history, falling back
     * to popularity-based recommendations when there's no usable history yet
     * (a brand new profile, or a history made entirely of non-TMDB ids) —
     * see [NuvioRecommender.recommendFor].
     */
    suspend fun recommendedPreviews(topK: Int = 20): List<MetaPreview> {
        // Whole body wrapped, not just individual steps: an uncaught
        // exception in a caller's LaunchedEffect crashes the entire app,
        // not just this screen (confirmed live 2026-08-28 - a missing
        // try/catch at ONE call site was enough to take the whole app
        // down on tap). This is the actual safety net; callers should
        // still handle a thrown exception gracefully too, defense in
        // depth, but must never rely on that alone.
        return try {
            recommendedPreviewsUnsafe(topK)
        } catch (t: Throwable) {
            Logger.w("RecommenderRepository") { "recommendedPreviews failed: ${t.message}" }
            emptyList()
        }
    }

    private suspend fun recommendedPreviewsUnsafe(topK: Int): List<MetaPreview> = coroutineScope {
        val engine = ensureLoaded() ?: return@coroutineScope emptyList()

        val entries = WatchProgressRepository.uiState.value.entries
        val historyIds = entries.filter { it.isCompleted }.mapNotNull { it.tmdbId() }
        val excludeIds = entries.mapNotNull { it.tmdbId() }.toSet()

        val recommendedIds = engine.recommendFor(historyIds, excludeIds, topK)
        if (recommendedIds.isEmpty()) return@coroutineScope emptyList()

        val settings = TmdbSettingsRepository.uiState.value
        recommendedIds.map { tmdbId ->
            async {
                val type = engine.typeFor(tmdbId) ?: "movie"
                runCatching { TmdbMetadataService.fetchStandaloneMeta(type, "tmdb:$tmdbId", settings) }.getOrNull()
            }
        }.mapNotNull { it.await() }.map { meta ->
            MetaPreview(
                id = meta.id,
                type = meta.type,
                name = meta.name,
                poster = meta.poster,
                banner = meta.background,
                logo = meta.logo,
                description = meta.description,
                releaseInfo = meta.releaseInfo,
            )
        }
    }
}
