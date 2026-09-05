package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAutoPlayPolicyTest {

    // streamAutoPlayPreferBingeGroup/streamAutoPlayReuseBingeGroup both default to true on
    // PlayerSettingsUiState, and that combination alone makes isEffectivelyEnabled() return
    // true regardless of mode - disable both here so these tests actually exercise the
    // mode-specific branch instead.

    @Test
    fun manualModeIsNotEffectivelyEnabled() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayPreferBingeGroup = false,
            streamAutoPlayReuseBingeGroup = false,
        )

        assertFalse(StreamAutoPlayPolicy.isEffectivelyEnabled(settings))
    }

    @Test
    fun networkQualityModeIsAlwaysEffectivelyEnabled() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.NETWORK_QUALITY,
            streamAutoPlayPreferBingeGroup = false,
            streamAutoPlayReuseBingeGroup = false,
        )

        assertTrue(StreamAutoPlayPolicy.isEffectivelyEnabled(settings))
    }

    @Test
    fun regexModeNeedsAConfiguredPattern() {
        val configured = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            streamAutoPlayRegex = "1080p",
            streamAutoPlayPreferBingeGroup = false,
            streamAutoPlayReuseBingeGroup = false,
        )
        val unconfigured = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            streamAutoPlayRegex = "",
            streamAutoPlayPreferBingeGroup = false,
            streamAutoPlayReuseBingeGroup = false,
        )

        assertTrue(StreamAutoPlayPolicy.isEffectivelyEnabled(configured))
        assertFalse(StreamAutoPlayPolicy.isEffectivelyEnabled(unconfigured))
    }
}
