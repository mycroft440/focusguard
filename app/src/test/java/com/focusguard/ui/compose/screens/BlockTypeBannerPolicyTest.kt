package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockTypeBannerPolicyTest {
    @Test
    fun dailyLimitShowsBanner() {
        assertThat(shouldShowBlockTypeBanner(BlockTypeUi.DAILY_LIMIT)).isTrue()
    }

    @Test
    fun timeBlockShowsBanner() {
        assertThat(shouldShowBlockTypeBanner(BlockTypeUi.DOPAMINE_FAST)).isTrue()
    }

    @Test
    fun passwordBlockDoesNotShowBanner() {
        assertThat(shouldShowBlockTypeBanner(BlockTypeUi.PASSWORD)).isFalse()
    }
}
