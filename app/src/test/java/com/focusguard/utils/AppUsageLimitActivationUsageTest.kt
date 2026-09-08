package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUsageLimitActivationUsageTest {

    @Test
    fun `first observation snapshots previous usage and starts new limit at zero`() {
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 30 * 60_000L
        val usageAlreadyConsumedBeforeLimit = 25 * 60_000L
        val baseline = AppUsageLimitActivationUsage.firstObservationBaselineMillis(
            usageAlreadyConsumedBeforeLimit
        )

        val effectiveUsage = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = usageAlreadyConsumedBeforeLimit,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )

        assertThat(baseline).isEqualTo(usageAlreadyConsumedBeforeLimit)
        assertThat(effectiveUsage).isEqualTo(0L)
    }

    @Test
    fun `one minute limit is reached only after one full post activation minute`() {
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 30 * 60_000L
        val usageAlreadyConsumedBeforeLimit = 25 * 60_000L
        val baseline = AppUsageLimitActivationUsage.firstObservationBaselineMillis(
            usageAlreadyConsumedBeforeLimit
        )

        val immediatelyAfterActivation = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = usageAlreadyConsumedBeforeLimit,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )
        val beforeOneMinute = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = usageAlreadyConsumedBeforeLimit + 59_999L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )
        val atOneMinute = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = usageAlreadyConsumedBeforeLimit + 60_000L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )

        assertThat(UsageLimitForegroundPolicy.usedMinutes(immediatelyAfterActivation)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(beforeOneMinute)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(atOneMinute)).isEqualTo(1L)
    }

    @Test
    fun `usage before activation is not charged to a new daily limit`() {
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 10 * 60_000L
        val baseline = 8 * 60_000L
        val currentDayUsage = 10 * 60_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = currentDayUsage,
                activationBaselineMillis = baseline,
                activatedAtMillis = activatedAt,
                dayStartMillis = dayStart
            )
        ).isEqualTo(2 * 60_000L)
    }

    @Test
    fun `limit becomes an ordinary midnight based daily allowance on later days`() {
        val dayStart = 10_000_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = 3 * 60_000L,
                activationBaselineMillis = 100 * 60_000L,
                activatedAtMillis = dayStart - 1L,
                dayStartMillis = dayStart
            )
        ).isEqualTo(3 * 60_000L)
    }

    @Test
    fun `counter never becomes negative if Android usage stats move backwards`() {
        val dayStart = 1_000_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = 2 * 60_000L,
                activationBaselineMillis = 5 * 60_000L,
                activatedAtMillis = dayStart + 1L,
                dayStartMillis = dayStart
            )
        ).isEqualTo(0L)
    }

    @Test
    fun `three minute allowance is reached only after three post activation minutes`() {
        val baseline = 25 * 60_000L
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 30 * 60_000L
        val beforeThreeMinutes = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = baseline + 179_999L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )
        val atThreeMinutes = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = baseline + 180_000L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )

        assertThat(UsageLimitForegroundPolicy.usedMinutes(beforeThreeMinutes)).isEqualTo(2L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(atThreeMinutes)).isEqualTo(3L)
    }
}
