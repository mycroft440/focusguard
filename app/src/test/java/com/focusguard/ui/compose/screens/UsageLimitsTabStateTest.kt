package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageLimitsTabStateTest {

    @Test
    fun `website tabs share configured count without sharing loaded state`() {
        val sharedState = WebsiteLimitsSharedState()
        val websiteState = WebsiteLimitsTabState(sharedState)
        val keywordState = WebsiteLimitsTabState(sharedState)

        sharedState.allConfiguredCount.intValue = 4
        websiteState.hasLoaded = true

        assertThat(keywordState.shared.allConfiguredCount.intValue).isEqualTo(4)
        assertThat(keywordState.hasLoaded).isFalse()
    }

    @Test
    fun `app tab state keeps loaded marker and search while reused`() {
        val state = AppLimitsTabState()

        state.searchQuery.value = "social"
        state.hasLoaded = true

        assertThat(state.searchQuery.value).isEqualTo("social")
        assertThat(state.hasLoaded).isTrue()
    }
}
