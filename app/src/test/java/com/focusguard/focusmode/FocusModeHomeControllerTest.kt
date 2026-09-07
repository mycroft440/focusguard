package com.focusguard.focusmode

import android.app.admin.DevicePolicyManager
import android.content.Intent
import com.focusguard.admin.DeviceOwnerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusModeHomeControllerTest {

    @Test
    fun `native kiosk blocks home and overview while preserving global power actions`() {
        val expected = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

        assertThat(FocusModeHomeController.requiredLockTaskFeatures()).isEqualTo(expected)
        assertThat(FocusModeHomeController.lockTaskFeaturesBlockHomeAndKeepPower(expected)).isTrue()
        assertThat(DeviceOwnerManager.lockTaskFeaturesKeepOnlyGlobalActions(expected)).isTrue()
        assertThat(
            FocusModeHomeController.lockTaskFeaturesBlockHomeAndKeepPower(
                expected or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
            )
        ).isFalse()
        assertThat(
            FocusModeHomeController.lockTaskFeaturesBlockHomeAndKeepPower(
                expected or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
            )
        ).isFalse()
    }

    @Test
    fun `temporary home filter remains a real default home fallback`() {
        val filter = FocusModeHomeController.homeIntentFilter()

        assertThat(filter.hasAction(Intent.ACTION_MAIN)).isTrue()
        assertThat(filter.hasCategory(Intent.CATEGORY_HOME)).isTrue()
        assertThat(filter.hasCategory(Intent.CATEGORY_DEFAULT)).isTrue()
    }
}
