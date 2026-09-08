package com.focusguard.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.database.AppUsageLimit

/**
 * Converts Android's day-wide UsageStats counter into usage accumulated after a
 * specific app limit was activated.
 *
 * The important invariant is that a newly-created limit starts at zero even when
 * the target app was already used earlier on the same day. Android's aggregate
 * counters are reliable for a midnight-to-now total, but two aggregate queries
 * with different arbitrary end times are not guaranteed to use matching buckets.
 * Therefore the first enforcement observation snapshots the exact day-wide
 * counter already supplied by the caller and persists it as the activation
 * baseline. Later observations subtract that same baseline from the same kind of
 * day-wide counter.
 *
 * The creation flow calls enforcement immediately after persisting the rule, so
 * the first snapshot represents the usage that existed when the rule was defined.
 * SharedPreferences keeps that baseline across process recreation. From the next
 * local midnight onward the activation predates the day and the rule naturally
 * becomes an ordinary midnight-based daily allowance.
 */
object AppUsageLimitActivationUsage {
    private const val PREFS_NAME = "app_usage_limit_activation_usage"
    private const val SUFFIX_ACTIVATED_AT = ".activated_at"
    private const val SUFFIX_DAY_START = ".day_start"
    private const val SUFFIX_BASELINE_MS = ".baseline_ms"

    private data class BaselineKey(
        val packageName: String,
        val activatedAtMillis: Long,
        val dayStartMillis: Long
    )

    private val memoryBaselines = mutableMapOf<BaselineKey, Long>()

    @Suppress("UNUSED_PARAMETER")
    fun effectiveUsageMillis(
        context: Context,
        usageStatsManager: UsageStatsManager,
        limit: AppUsageLimit,
        currentDayUsageMillis: Long,
        dayStartMillis: Long,
        nowMillis: Long
    ): Long {
        val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
        val activatedAt = limit.createdAt

        // A limit created before this local day gets the ordinary midnight reset.
        if (activatedAt <= dayStartMillis) return currentUsage

        // A wall-clock correction must never make a newly-created limit inherit
        // usage from before its apparent activation time.
        if (activatedAt > nowMillis) return 0L

        val baseline = readOrCreateBaseline(
            context = context,
            packageName = limit.packageName,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStartMillis,
            currentDayUsageMillis = currentUsage
        ) ?: return 0L

        return usageSinceActivationMillis(
            currentDayUsageMillis = currentUsage,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStartMillis
        )
    }

    /** Pure calculation kept public for deterministic unit coverage. */
    fun usageSinceActivationMillis(
        currentDayUsageMillis: Long,
        activationBaselineMillis: Long,
        activatedAtMillis: Long,
        dayStartMillis: Long
    ): Long {
        val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
        if (activatedAtMillis <= dayStartMillis) return currentUsage
        return (currentUsage - activationBaselineMillis.coerceAtLeast(0L))
            .coerceAtLeast(0L)
    }

    /** The first observation becomes zero by subtracting this exact snapshot. */
    internal fun firstObservationBaselineMillis(currentDayUsageMillis: Long): Long =
        currentDayUsageMillis.coerceAtLeast(0L)

    @Synchronized
    private fun readOrCreateBaseline(
        context: Context,
        packageName: String,
        activatedAtMillis: Long,
        dayStartMillis: Long,
        currentDayUsageMillis: Long
    ): Long? {
        if (packageName.isBlank()) return null
        val cacheKey = BaselineKey(packageName, activatedAtMillis, dayStartMillis)
        memoryBaselines[cacheKey]?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val keyPrefix = packageName
        val storedActivation = prefs.getLong(keyPrefix + SUFFIX_ACTIVATED_AT, Long.MIN_VALUE)
        val storedDayStart = prefs.getLong(keyPrefix + SUFFIX_DAY_START, Long.MIN_VALUE)
        if (storedActivation == activatedAtMillis && storedDayStart == dayStartMillis) {
            val persisted = prefs.getLong(keyPrefix + SUFFIX_BASELINE_MS, 0L)
                .coerceAtLeast(0L)
            memoryBaselines[cacheKey] = persisted
            return persisted
        }

        // Never persist a fake snapshot without Usage Access. The next pulse can
        // capture the real day-wide counter after the permission is restored.
        if (!PermissionUtils.isUsageAccessEnabled(context)) return null

        // Use the caller's midnight-to-now counter itself as the activation
        // snapshot. This guarantees that pre-activation usage cannot leak into
        // the delta because both sides of the subtraction come from the same
        // counter series instead of two differently bucketed Android queries.
        val baseline = firstObservationBaselineMillis(currentDayUsageMillis)

        memoryBaselines[cacheKey] = baseline
        prefs.edit()
            .putLong(keyPrefix + SUFFIX_ACTIVATED_AT, activatedAtMillis)
            .putLong(keyPrefix + SUFFIX_DAY_START, dayStartMillis)
            .putLong(keyPrefix + SUFFIX_BASELINE_MS, baseline)
            .apply()
        return baseline
    }
}
