package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetExitEchoPolicyTest {

    private val target = "com.brave.browser"

    @Test
    fun `android chooser foreground suppresses stale target window after exit`() {
        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitEcho(
                target = target,
                observedForegroundPackage = "android",
                elapsedSinceExitMillis = 180L
            )
        ).isTrue()
    }

    @Test
    fun `launcher foreground suppresses stale target window after home`() {
        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitEcho(
                target = target,
                observedForegroundPackage = "com.android.launcher3",
                elapsedSinceExitMillis = 240L
            )
        ).isTrue()
    }

    @Test
    fun `protected target foreground again is a real new entry`() {
        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitEcho(
                target = target,
                observedForegroundPackage = target,
                elapsedSinceExitMillis = 320L
            )
        ).isFalse()
    }

    @Test
    fun `unknown foreground fails closed instead of hiding a real entry`() {
        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitEcho(
                target = target,
                observedForegroundPackage = null,
                elapsedSinceExitMillis = 200L
            )
        ).isFalse()
    }

    @Test
    fun `old exit cannot suppress a later app entry`() {
        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitEcho(
                target = target,
                observedForegroundPackage = "com.android.launcher3",
                elapsedSinceExitMillis = 4_001L
            )
        ).isFalse()
    }
}
