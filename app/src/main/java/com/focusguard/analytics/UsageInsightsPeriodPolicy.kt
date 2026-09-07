package com.focusguard.analytics

import java.time.Instant
import java.time.ZoneId

/** Calendar-month interval used by the "most used" ranking. */
data class CurrentMonthUsagePeriod(
    val startMillis: Long,
    val endMillis: Long,
    val elapsedDays: Int
)

object UsageInsightsPeriodPolicy {
    fun currentMonth(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CurrentMonthUsagePeriod {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val start = now.toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(zoneId)

        return CurrentMonthUsagePeriod(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = nowMillis,
            elapsedDays = now.dayOfMonth.coerceAtLeast(1)
        )
    }
}
