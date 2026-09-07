package com.focusguard.analytics

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a long-window usage ranking from Android's aggregated usage history.
 *
 * Monthly rankings deliberately prefer aggregate history over lifecycle events:
 * several Android builds retain aggregate buckets longer than detailed events.
 */
object MonthlyMostUsedAppsProvider {
    suspend fun load(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppUsageStat> = withContext(Dispatchers.IO) {
        require(endTime > startTime) { "endTime must be after startTime" }

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()
        val packageManager = context.packageManager

        try {
            manager.queryAndAggregateUsageStats(startTime, endTime)
                .values
                .asSequence()
                .filter { usage ->
                    usage.totalTimeInForeground > 60_000L &&
                        runCatching {
                            packageManager.getLaunchIntentForPackage(usage.packageName) != null
                        }.getOrDefault(false)
                }
                .map { usage ->
                    AppUsageStat(
                        packageName = usage.packageName,
                        timeSpentMs = usage.totalTimeInForeground
                            .coerceAtMost(endTime - startTime)
                    )
                }
                .sortedByDescending(AppUsageStat::timeSpentMs)
                .toList()
        } catch (error: Throwable) {
            FocusGuardLogger.logError(
                "MonthlyMostUsedApps",
                "Falha ao carregar ranking mensal agregado",
                error
            )
            emptyList()
        }
    }
}
