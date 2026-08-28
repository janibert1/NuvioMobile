package com.nuvio.app.features.recommender

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.posterGridColumnCountForWidth

/**
 * Minimal demo screen for the on-device recommender — see
 * https://github.com/janibert1/nuvio-recommender for the training side.
 * Deliberately plain: a header and a poster grid, reusing existing
 * PosterGridRow/NuvioScreen exactly the way LibraryScreen does. This is a
 * proof that the model works end-to-end inside the real app, not a
 * finished feature design (empty/loading states are intentionally basic).
 */
@Composable
fun RecommendationsScreen(
    onBack: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    var previews by remember { mutableStateOf<List<MetaPreview>?>(null) }

    LaunchedEffect(Unit) {
        // An uncaught exception here crashes the whole app, not just this
        // screen - a LaunchedEffect coroutine's exception isn't contained
        // to the composable that launched it. Confirmed the hard way: this
        // was missing and Jan's tap crashed the entire app (2026-08-28).
        previews = try {
            RecommenderRepository.recommendedPreviews(topK = 20)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridColumns = remember(maxWidth) { posterGridColumnCountForWidth(maxWidth) }
        val loaded = previews

        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            item {
                NuvioScreenHeader(title = "Recommended for you", onBack = onBack)
            }

            when {
                loaded == null -> item {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                loaded.isEmpty() -> item {
                    Text(
                        text = "No recommendations yet — watch a few things first.",
                        style = MaterialTheme.typography.bodyMedium,
                        // Explicit color: without it this rendered as
                        // low-contrast dark-gray-on-black (reported live,
                        // 2026-08-28) - Text() without an explicit color
                        // falls back to LocalContentColor here, which
                        // isn't guaranteed readable against this screen's
                        // background the way it is on screens that sit
                        // inside a Surface with a matching contentColor.
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                else -> items(
                    items = loaded.chunked(gridColumns),
                    key = { row -> row.first().let { "${it.type}:${it.id}" } },
                ) { row ->
                    PosterGridRow(
                        items = row,
                        columns = gridColumns,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onPosterClick = onPosterClick,
                    )
                }
            }
        }
    }
}
