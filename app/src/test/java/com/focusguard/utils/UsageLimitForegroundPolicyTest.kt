package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageLimitForegroundPolicyTest {

    @Test
    fun `app limit uses only reported foreground milliseconds`() {
        assertThat(UsageLimitForegroundPolicy.usedMinutes(-1L)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(59_999L)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(60_000L)).isEqualTo(1L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(150_000L)).isEqualTo(2L)
    }

    @Test
    fun `website usage counts only while tracked browser is foreground and screen is on`() {
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = true
            )
        ).isTrue()

        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.example.other",
                isDeviceInteractive = true
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = false
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = null,
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = true
            )
        ).isFalse()
    }

    @Test
    fun `measurement pulse skips FocusGuard launcher and apps without an active limit`() {
        val focusGuard = "com.focusguard.v2"
        val launcher = "com.example.launcher"
        val limitedApp = "com.example.video"
        val activeLimitPackages = setOf(limitedApp, focusGuard)

        assertThat(
            UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                foregroundPackageName = focusGuard,
                activeLimitPackages = activeLimitPackages,
                focusGuardPackageName = focusGuard,
                launcherPackageName = launcher,
                isDeviceInteractive = true
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                foregroundPackageName = launcher,
                activeLimitPackages = activeLimitPackages,
                focusGuardPackageName = focusGuard,
                launcherPackageName = launcher,
                isDeviceInteractive = true
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                foregroundPackageName = "com.example.other",
                activeLimitPackages = activeLimitPackages,
                focusGuardPackageName = focusGuard,
                launcherPackageName = launcher,
                isDeviceInteractive = true
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                foregroundPackageName = limitedApp,
                activeLimitPackages = activeLimitPackages,
                focusGuardPackageName = focusGuard,
                launcherPackageName = launcher,
                isDeviceInteractive = true
            )
        ).isTrue()
        assertThat(
            UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                foregroundPackageName = limitedApp,
                activeLimitPackages = activeLimitPackages,
                focusGuardPackageName = focusGuard,
                launcherPackageName = launcher,
                isDeviceInteractive = false
            )
        ).isFalse()
    }

    @Test
    fun `an exceeded app is enforced while it remains in the foreground`() {
        assertThat(
            UsageLimitForegroundPolicy.shouldEnforceCurrentApp(
                foregroundPackageName = "com.example.video",
                exceededPackages = setOf("com.example.video"),
                focusGuardPackageName = "com.focusguard.v2",
                launcherPackageName = "com.example.launcher",
                isDeviceInteractive = true
            )
        ).isTrue()

        assertThat(
            UsageLimitForegroundPolicy.shouldEnforceCurrentApp(
                foregroundPackageName = "com.example.video",
                exceededPackages = setOf("com.example.video"),
                focusGuardPackageName = "com.focusguard.v2",
                launcherPackageName = "com.example.launcher",
                isDeviceInteractive = false
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldEnforceCurrentApp(
                foregroundPackageName = "com.focusguard.v2",
                exceededPackages = setOf("com.focusguard.v2"),
                focusGuardPackageName = "com.focusguard.v2",
                launcherPackageName = "com.example.launcher",
                isDeviceInteractive = true
            )
        ).isFalse()
    }
}
