package com.nuvio.app.features.recommender

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res

@Serializable
internal data class RecommenderManifest(
    val embed_dim: Int,
    val hidden_dim: Int,
    val catalog_size: Int,
)

/**
 * On-device recommendation engine.
 *
 * A hand-written forward pass for the small "user tower" half of a
 * two-tower retrieval model trained offline
 * (https://github.com/janibert1/nuvio-recommender) on MovieLens 25M
 * (movies) plus TMDB "similar shows" weak supervision (TV) — see that
 * repo's README for the full training approach.
 *
 * Deliberately hand-written instead of using an ML inference runtime
 * (ONNX Runtime / Core ML / TFLite): the whole model is an embedding
 * lookup plus a 2-layer MLP, small enough that a real runtime dependency
 * would be pure overhead — and NuvioMobile's own CONTRIBUTING.md flags
 * unapproved dependency additions as an automatic close-without-review.
 * This file has zero new dependencies; kotlinx.serialization (already a
 * project dependency, see Routes.kt) is the only non-stdlib import.
 *
 * The ITEM side of the model never runs on-device at all. It ran once,
 * offline, over the whole movie/TV catalog during training
 * (export_weights.py in the training repo); only the resulting vectors
 * ship here, in catalog_item_vectors.bin. Recommending is then just:
 * look up the user's watched items' precomputed vectors (a table lookup,
 * not a model forward pass), mean-pool them, run that pooled vector
 * through the small user MLP below, then rank the whole catalog by dot
 * product. A history item that isn't in the shipped catalog (something
 * outside the training set, or an addon-sourced id we can't map to a
 * TMDB id) is simply skipped rather than scored — graceful degradation,
 * not a crash or a stale placeholder embedding.
 */
class NuvioRecommender private constructor(
    private val embedDim: Int,
    private val hiddenDim: Int,
    private val userMlp0Weight: FloatArray, // (hiddenDim, embedDim), PyTorch row-major
    private val userMlp0Bias: FloatArray,
    private val userMlp2Weight: FloatArray, // (embedDim, hiddenDim), PyTorch row-major
    private val userMlp2Bias: FloatArray,
    private val catalogIds: List<Int>,
    private val catalogTypes: List<String>, // "movie"/"tv", parallel to catalogIds
    private val catalogItemVectors: FloatArray, // (catalogSize * embedDim), row-major
    private val popularityRank: List<Int>,
) {
    private val idToRow: Map<Int, Int> = catalogIds.withIndex().associate { (i, id) -> id to i }

    /** True if this tmdb id is in the shipped catalog and can be scored/recommended. */
    fun knowsItem(tmdbId: Int): Boolean = tmdbId in idToRow

    /** "movie" or "tv" for a catalog id, so callers don't have to guess which TMDB endpoint to resolve it against. Null if [tmdbId] isn't in this build's catalog. */
    fun typeFor(tmdbId: Int): String? = idToRow[tmdbId]?.let { catalogTypes[it] }

    /**
     * Mean-pools the precomputed vectors of whichever [historyTmdbIds] are in the
     * shipped catalog, then runs the user-tower MLP. Returns null if none of the
     * history is in the catalog (brand new user, or a history made entirely of
     * items outside this build's catalog) — callers should fall back to
     * [recommendPopular] in that case, which [recommendFor] already does.
     */
    fun userVectorFor(historyTmdbIds: List<Int>): FloatArray? {
        val rows = historyTmdbIds.mapNotNull { idToRow[it] }
        if (rows.isEmpty()) return null

        val pooled = FloatArray(embedDim)
        for (row in rows) {
            val base = row * embedDim
            for (d in 0 until embedDim) pooled[d] += catalogItemVectors[base + d]
        }
        val invCount = 1f / rows.size
        for (d in 0 until embedDim) pooled[d] *= invCount

        val hidden = FloatArray(hiddenDim)
        for (h in 0 until hiddenDim) {
            var sum = userMlp0Bias[h]
            val wBase = h * embedDim
            for (d in 0 until embedDim) sum += userMlp0Weight[wBase + d] * pooled[d]
            hidden[h] = if (sum > 0f) sum else 0f // ReLU
        }

        val out = FloatArray(embedDim)
        for (o in 0 until embedDim) {
            var sum = userMlp2Bias[o]
            val wBase = o * hiddenDim
            for (h in 0 until hiddenDim) sum += userMlp2Weight[wBase + h] * hidden[h]
            out[o] = sum
        }
        return l2Normalize(out)
    }

    /** Ranks the whole shipped catalog by dot product (cosine, both sides are L2-normalized) against [userVector]. */
    fun recommend(userVector: FloatArray, excludeIds: Set<Int>, topK: Int = 20): List<Int> {
        val scored = ArrayList<Pair<Int, Float>>(catalogIds.size)
        for ((row, id) in catalogIds.withIndex()) {
            if (id in excludeIds) continue
            val base = row * embedDim
            var dot = 0f
            for (d in 0 until embedDim) dot += userVector[d] * catalogItemVectors[base + d]
            scored += id to dot
        }
        return scored.sortedByDescending { it.second }.take(topK).map { it.first }
    }

    /** Cold start: popularity ranking (MovieLens rating count for movies, TMDB popularity for TV) — no personalization possible without any known history. */
    fun recommendPopular(excludeIds: Set<Int>, topK: Int = 20): List<Int> =
        popularityRank.asSequence().filter { it !in excludeIds }.take(topK).toList()

    /** The one method most callers actually want: personalizes if there's usable history, falls back to popularity otherwise. */
    fun recommendFor(historyTmdbIds: List<Int>, excludeIds: Set<Int>, topK: Int = 20): List<Int> {
        val userVector = userVectorFor(historyTmdbIds)
        return if (userVector != null) recommend(userVector, excludeIds, topK) else recommendPopular(excludeIds, topK)
    }

    companion object {
        private const val BASE = "files/recommender/"

        suspend fun load(): NuvioRecommender {
            val manifest = Json.decodeFromString<RecommenderManifest>(
                Res.readBytes("$BASE" + "manifest.json").decodeToString()
            )
            val catalogIds = Json.decodeFromString<List<Int>>(
                Res.readBytes("$BASE" + "catalog_ids.json").decodeToString()
            )
            val catalogTypes = Json.decodeFromString<List<String>>(
                Res.readBytes("$BASE" + "catalog_types.json").decodeToString()
            )
            val popularityRank = Json.decodeFromString<List<Int>>(
                Res.readBytes("$BASE" + "popularity_rank.json").decodeToString()
            )
            return NuvioRecommender(
                embedDim = manifest.embed_dim,
                hiddenDim = manifest.hidden_dim,
                userMlp0Weight = Res.readBytes("$BASE" + "user_mlp_0_weight.bin").toFloatArrayLE(),
                userMlp0Bias = Res.readBytes("$BASE" + "user_mlp_0_bias.bin").toFloatArrayLE(),
                userMlp2Weight = Res.readBytes("$BASE" + "user_mlp_2_weight.bin").toFloatArrayLE(),
                userMlp2Bias = Res.readBytes("$BASE" + "user_mlp_2_bias.bin").toFloatArrayLE(),
                catalogIds = catalogIds,
                catalogTypes = catalogTypes,
                catalogItemVectors = Res.readBytes("$BASE" + "catalog_item_vectors.bin").toFloatArrayLE(),
                popularityRank = popularityRank,
            )
        }
    }
}

private fun l2Normalize(v: FloatArray): FloatArray {
    var sumSq = 0f
    for (x in v) sumSq += x * x
    val norm = kotlin.math.sqrt(sumSq).coerceAtLeast(1e-8f)
    return FloatArray(v.size) { v[it] / norm }
}

/**
 * Little-endian float32 decode, matching numpy's `ndarray.tofile()` default
 * byte order (the format export_weights.py writes). Hand-rolled with plain
 * bit manipulation rather than java.nio.ByteBuffer, which is JVM/Android-only
 * — this needs to work in iosMain too, and Float.fromBits is pure Kotlin
 * stdlib available on every KMP target.
 */
private fun ByteArray.toFloatArrayLE(): FloatArray {
    val out = FloatArray(size / 4)
    for (i in out.indices) {
        val base = i * 4
        val bits = (this[base].toInt() and 0xFF) or
            ((this[base + 1].toInt() and 0xFF) shl 8) or
            ((this[base + 2].toInt() and 0xFF) shl 16) or
            ((this[base + 3].toInt() and 0xFF) shl 24)
        out[i] = Float.fromBits(bits)
    }
    return out
}
