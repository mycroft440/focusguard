package com.focusguard.manager

import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockingSessionManagerTargetsTest {

    @Test
    fun `password and daily limit targets can coexist while exclusive targets cannot`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.example.password", "com.example.both"),
            passwordSessionWebsiteRules = listOf("youtube.com", "keyword:porn"),
            exclusiveSessionAppPackages = listOf("com.example.dopamine"),
            exclusiveSessionWebsiteRules = listOf("reddit.com"),
            limitedAppPackages = listOf("com.example.limit", "com.example.both"),
            limitedWebsiteRules = listOf("https://www.youtube.com/watch?v=1")
        )

        assertThat(targets.passwordAppPackageNames).containsAtLeast(
            "com.example.password",
            "com.example.both"
        )
        assertThat(targets.passwordAppPackageNames)
            .doesNotContain("com.google.android.youtube")
        assertThat(targets.limitedAppPackageNames).containsAtLeast(
            "com.example.limit",
            "com.example.both"
        )
        assertThat(targets.limitedAppPackageNames)
            .doesNotContain("com.google.android.youtube")
        assertThat(targets.exclusiveAppPackageNames).contains("com.example.dopamine")
        assertThat(targets.unavailableAppPackageNames).containsAtLeast(
            "com.example.both",
            "com.example.dopamine"
        )
        assertThat(targets.unavailableAppPackageNames)
            .doesNotContain("com.google.android.youtube")
        assertThat(targets.unavailableWebsiteRules).containsExactly(
            "youtube.com",
            "reddit.com"
        )
        assertThat(targets.unavailableWebsiteRules).doesNotContain("keyword:porn")
    }

    @Test
    fun `password-only and limit-only targets remain available for compatible protection`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.example.password"),
            passwordSessionWebsiteRules = listOf("keyword:porn"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = listOf("com.example.limit"),
            limitedWebsiteRules = listOf("reddit.com")
        )

        assertThat(targets.allAppPackageNames).containsAtLeast(
            "com.example.password",
            "com.example.limit"
        )
        assertThat(targets.unavailableAppPackageNames).doesNotContain("com.example.password")
        assertThat(targets.unavailableAppPackageNames).doesNotContain("com.example.limit")
        assertThat(targets.unavailableWebsiteRules).isEmpty()
    }

    @Test
    fun `semantic overlap between password keyword and limited domain is unavailable`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = emptyList(),
            passwordSessionWebsiteRules = listOf("keyword:porn"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = emptyList(),
            limitedWebsiteRules = listOf("example-porn-site.com")
        )

        assertThat(targets.unavailableWebsiteRules).containsExactly("example-porn-site.com")
        assertThat(targets.unavailableWebsiteRules).doesNotContain("keyword:porn")
    }

    @Test
    fun `pornography category semantically covers its internal rules`() {
        assertThat(
            BlockingSessionManager.isWebsiteRuleCoveredBy(
                "keyword:xvideo",
                listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isTrue()
        assertThat(
            BlockingSessionManager.isWebsiteRuleCoveredBy(
                "onlyfans.com",
                listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isTrue()
    }

    @Test
    fun `blank and duplicate targets are removed`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.example.app", ""),
            passwordSessionWebsiteRules = listOf("youtube.com"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = listOf("com.example.app", "   "),
            limitedWebsiteRules = listOf("https://www.youtube.com/watch?v=1")
        )

        assertThat(targets.allAppPackageNames).containsExactly("com.example.app")
        assertThat(targets.allWebsiteRules).containsExactly("youtube.com")
    }

    @Test
    fun `YouTube app does not reserve the YouTube website surface`() {
        val appOnly = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.google.android.youtube"),
            passwordSessionWebsiteRules = emptyList(),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = emptyList(),
            limitedWebsiteRules = emptyList()
        )

        assertThat(appOnly.allAppPackageNames)
            .containsExactly("com.google.android.youtube")
        assertThat(appOnly.allWebsiteRules).isEmpty()

        val siteOnly = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = emptyList(),
            passwordSessionWebsiteRules = listOf("youtube.com"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = emptyList(),
            limitedWebsiteRules = emptyList()
        )

        assertThat(siteOnly.allWebsiteRules).containsExactly("youtube.com")
        assertThat(siteOnly.allAppPackageNames).isEmpty()
    }

    @Test
    fun `non-blocking pomodoro does not participate in blocking policies`() {
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = false)
            )
        ).isFalse()
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = true)
            )
        ).isTrue()
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "PASSWORD", isBlockingEnabled = false)
            )
        ).isTrue()
    }

    @Test
    fun `password session must match the blocked target`() {
        assertThat(
            BlockingSessionManager.matchesBlockedTarget(
                blockedPackage = "com.example.blocked",
                blockedDomain = null,
                sessionApps = setOf("com.example.other"),
                sessionSites = emptySet()
            )
        ).isFalse()
        assertThat(
            BlockingSessionManager.matchesBlockedTarget(
                blockedPackage = null,
                blockedDomain = "news.example.com",
                sessionApps = emptySet(),
                sessionSites = setOf("example.com")
            )
        ).isTrue()
    }

    @Test
    fun `adult filter alone arms self protection`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `active focus mode arms self protection even when its blocked list is empty`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = false,
                focusModeActive = true
            )
        ).isTrue()
    }

    @Test
    fun `self protection disarms only when no target is being blocked`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun `device owner keeps password only target launchable for accessibility auth`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = emptyList()
            )
        ).isEmpty()
    }

    @Test
    fun `device owner suspends password target once daily limit becomes stronger`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = listOf("com.example.password")
            )
        ).containsExactly("com.example.password")
    }

    @Test
    fun `device owner keeps unrelated non password targets suspended`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password", "com.example.time"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = listOf("com.example.time")
            )
        ).containsExactly("com.example.time")
    }

    @Test
    fun `strict pomodoro suspends password target too`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password", "com.example.other"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = emptyList(),
                strictPomodoro = true
            )
        ).containsExactly("com.example.password", "com.example.other").inOrder()
    }
}
