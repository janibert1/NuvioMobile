package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.transcode.TranscodeLadder
import com.nuvio.app.core.transcode.TranscodeTier

/** Current transcode quality ("Auto · 5 Mbps") + manual override menu. */
internal data class PlayerQualityMenu(
    val label: String,
    /** null == automatic. */
    val manualTier: TranscodeTier?,
    val onSelect: (TranscodeTier?) -> Unit,
)

@Composable
internal fun PlayerQualityChip(menu: PlayerQualityMenu, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(menu.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (menu.manualTier == null) "✓ Auto" else "Auto") },
                onClick = { expanded = false; menu.onSelect(null) },
            )
            TranscodeLadder.tiers.asReversed().forEach { tier ->
                val selected = menu.manualTier?.videoKbps == tier.videoKbps
                DropdownMenuItem(
                    text = { Text((if (selected) "✓ " else "") + tier.label) },
                    onClick = { expanded = false; menu.onSelect(tier) },
                )
            }
        }
    }
}
