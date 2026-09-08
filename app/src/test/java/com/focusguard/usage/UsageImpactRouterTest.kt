package com.focusguard.usage

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.utils.UsageLimitBehaviorPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageImpactRouterTest {
    private val now = 1_000_000L

    private fun session(
        type: String = "TIME",
        active: Boolean = true,
        startTime: Long = now - 10_000L,
        endTime: Long? = now + 10_000L
    ) = BlockSession(
        id = 7,
        startTime = startTime,
        endTime = endTime,
        isActive = active,
        sessionType = type,
        isFixed24h = true
    )

    private fun limit(
        packageName: String,
        dailyMinutes: Int = 30,
        lockMode: String = UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(packageName),
        lockUntilTimestamp: Long? = now + 3L * 24L * 60L * 60L * 1_000L,
        enabled: Boolean = true,
        preventOpeningAfterLimit: Boolean = true
    ) = AppUsageLimit(
        packageName = packageName,
        appName = packageName,
        dailyLimitMinutes = dailyMinutes,
        isEnabled = enabled,
        lockMode = lockMode,
        lockUntilTimestamp = lockUntilTimestamp,
        preventOpeningAfterLimit = preventOpeningAfterLimit
    )

    @Test
    fun `active time session is eligible for impact metrics`() {
        assertThat(UsageImpactRouter.isTimedSessionCandidate(session(), now)).isTrue()
    }

    @Test
    fun `password session never enters timed impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(session(type = "PASSWORD"), now)
        ).isFalse()
    }

    @Test
    fun `expired time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(
                session(endTime = now),
                now
            )
        ).isFalse()
    }

    @Test
    fun `future time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(
                session(startTime = now + 1L),
                now
            )
        ).isFalse()
    }

    @Test
    fun `inactive time session does not enter impact route`() {
        assertThat(
            UsageImpactRouter.isTimedSessionCandidate(session(active = false), now)
        ).isFalse()
    }

    @Test
    fun `configured daily limit with allowance remaining does not own password target`() {
        val configured = limit("com.example.remaining")

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 29L * 60_000L,
                nowMillis = now
            )
        ).isFalse()
    }

    @Test
    fun `block until tomorrow owns target only after allowance is exhausted`() {
        val configured = limit("com.example.tomorrow")

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 30L * 60_000L,
                nowMillis = now
            )
        ).isTrue()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 0L,
                nowMillis = now + 24L * 60L * 60L * 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `pause owns target for thirty minutes then releases it on same day`() {
        val packageName = "com.example.pause.same.day"
        val configured = limit(
            packageName = packageName,
            lockMode = UsageLimitBehaviorPolicy.pauseModeFor(packageName)
        )
        val exhausted = 30L * 60_000L

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = exhausted,
                nowMillis = now
            )
        ).isTrue()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = exhausted,
                nowMillis = now + UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS + 1L
            )
        ).isFalse()
    }

    @Test
    fun `pause can own target again on next local day`() {
        val packageName = "com.example.pause.next.day"
        val configured = limit(
            packageName = packageName,
            lockMode = UsageLimitBehaviorPolicy.pauseModeFor(packageName)
        )
        val exhausted = 30L * 60_000L

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = exhausted,
                nowMillis = now
            )
        ).isTrue()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = exhausted,
                nowMillis = now + 24L * 60L * 60L * 1_000L
            )
        ).isTrue()
    }

    @Test
    fun `time hardened usage limit still respects daily allowance`() {
        val configured = limit(
            packageName = "com.example.time.limit",
            lockMode = "TIME",
            lockUntilTimestamp = now + 60_000L
        )

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 29L * 60_000L,
                nowMillis = now
            )
        ).isFalse()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 30L * 60_000L,
                nowMillis = now
            )
        ).isTrue()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 30L * 60_000L,
                nowMillis = now + 60_000L
            )
        ).isFalse()
    }

    @Test
    fun `password mode usage limit never enters non password impact route`() {
        val configured = limit(
            packageName = "com.example.password.limit",
            lockMode = "PASSWORD",
            lockUntilTimestamp = null
        )

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(
                limit = configured,
                usedMillis = 60L * 60_000L,
                nowMillis = now
            )
        ).isFalse()
    }

    @Test
    fun `disabled or warning only limit never owns interception`() {
        val disabled = limit("com.example.disabled", enabled = false)
        val warning = limit("com.example.warning", lockMode = "WARNING")
        val exhausted = 60L * 60_000L

        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(disabled, exhausted, now)
        ).isFalse()
        assertThat(
            UsageImpactRouter.shouldRouteConfiguredAppLimit(warning, exhausted, now)
        ).isFalse()
    }
}
