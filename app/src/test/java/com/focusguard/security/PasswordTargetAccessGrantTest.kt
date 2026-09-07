package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetAccessGrantTest {

    private val target = "com.example.browser"

    @Test
    fun `internal activity transition in same package keeps one visit grant`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 200L,
            latestNonTargetForegroundAt = Long.MIN_VALUE,
            // Browsers can report the outgoing Activity background/stopped after
            // the new Activity from the same package is already foreground.
            latestTargetPackageBackgroundAt = 250L,
            latestTargetStoppedAt = 260L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `same package resume after background keeps one visit grant`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 300L,
            latestNonTargetForegroundAt = Long.MIN_VALUE,
            latestTargetPackageBackgroundAt = 220L,
            latestTargetStoppedAt = 230L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `foreground transition to another package ends one visit grant`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.android.launcher",
            latestTargetForegroundAt = 200L,
            latestNonTargetForegroundAt = 320L,
            latestTargetPackageBackgroundAt = 250L,
            latestTargetStoppedAt = 270L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `oem exit fallback revokes when target never resumes`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = null,
            latestTargetForegroundAt = 200L,
            latestNonTargetForegroundAt = Long.MIN_VALUE,
            latestTargetPackageBackgroundAt = 300L,
            latestTargetStoppedAt = 320L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `grant cannot be revoked before authenticated target reaches foreground`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.android.launcher",
            latestTargetForegroundAt = Long.MIN_VALUE,
            latestNonTargetForegroundAt = 300L,
            latestTargetPackageBackgroundAt = Long.MIN_VALUE,
            latestTargetStoppedAt = Long.MIN_VALUE
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = false,
                visitStartedAt = Long.MIN_VALUE,
                observation = observation
            )
        ).isFalse()
    }
}
