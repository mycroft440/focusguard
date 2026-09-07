package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class UsageInsightsPeriodPolicyTest {
    @Test
    fun currentMonthStartsAtLocalMidnightOnFirstDay() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = ZonedDateTime.of(2026, 9, 7, 4, 12, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        val period = UsageInsightsPeriodPolicy.currentMonth(now, zone)

        val expectedStart = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        assertThat(period.startMillis).isEqualTo(expectedStart)
        assertThat(period.endMillis).isEqualTo(now)
        assertThat(period.elapsedDays).isEqualTo(7)
    }

    @Test
    fun currentMonthHandlesYearBoundary() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2027, 1, 1, 0, 5, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        val period = UsageInsightsPeriodPolicy.currentMonth(now, zone)

        assertThat(period.startMillis).isEqualTo(
            ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        )
        assertThat(period.elapsedDays).isEqualTo(1)
    }

    @Test
    fun currentMonthUsesDeviceZoneRatherThanUtcMonth() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = ZonedDateTime.of(2026, 8, 31, 17, 30, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        val period = UsageInsightsPeriodPolicy.currentMonth(now, zone)

        assertThat(period.startMillis).isEqualTo(
            ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        )
        assertThat(period.elapsedDays).isEqualTo(31)
    }
}
