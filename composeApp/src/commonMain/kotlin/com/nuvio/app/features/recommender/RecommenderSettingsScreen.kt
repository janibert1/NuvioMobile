package com.nuvio.app.features.recommender

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.settings.SettingsNavigationRow

/**
 * Settings landing page for the on-device recommender — explains what it
 * is (privacy-focused: no account, no server, everything computed
 * on-device), shows real stats read from the shipped model's own
 * manifest, and hands off to [RecommendationsScreen] for the actual
 * poster grid. Kept as its own page rather than folding straight into the
 * poster grid, matching how every other feature in Settings (Downloads,
 * Collections, Licenses & Attributions) is structured: a settings page
 * first, content second.
 */
@Composable
fun RecommenderSettingsScreen(
    onBack: (() -> Unit)? = null,
    onViewRecommendations: () -> Unit,
) {
    var manifest by remember { mutableStateOf<RecommenderManifest?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manifest = try {
            NuvioRecommender.loadManifest()
        } catch (t: Throwable) {
            loadFailed = true
            null
        }
    }

    NuvioScreen(modifier = Modifier) {
        item {
            NuvioScreenHeader(title = "Recommendations", onBack = onBack)
        }

        item {
            NuvioSurfaceCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "How this works",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A small recommendation model runs entirely on this device — " +
                        "no account, no server, nothing sent anywhere. It looks at what " +
                        "you've watched and suggests similar movies and shows from a " +
                        "catalog that shipped with the app. With no watch history yet, " +
                        "it falls back to what's generally popular.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Experimental — proof of concept for " +
                        "https://github.com/NuvioMedia/NuvioMobile/issues/1803. " +
                        "TV recommendations are weaker than movie ones right now (see " +
                        "the linked issue for why).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            NuvioSurfaceCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Model catalog",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loadFailed -> Text(
                        text = "Couldn't load the model info.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    manifest == null -> Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        val m = manifest!!
                        StatRow(Icons.Rounded.Movie, "${m.movie_count} movies")
                        StatRow(Icons.Rounded.Tv, "${m.tv_count} TV shows")
                        if (m.trained_date.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Trained ${m.trained_date}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsNavigationRow(
                title = "View recommendations",
                description = "See what it suggests for you right now",
                icon = Icons.Rounded.Star,
                isTablet = false,
                onClick = onViewRecommendations,
            )
        }
    }
}

@Composable
private fun StatRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
