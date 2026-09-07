package com.focusguard.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.AppUsageLimitActivationUsage
import com.focusguard.utils.UsageLimitForegroundPolicy
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides whether an intercepted app must open the before/after impact metrics.
 *
 * Password blocks and Pomodoro never enter this route. A true TIME session and a
 * non-password usage-limit intervention do. TIME sessions are resolved first so
 * they do not depend on an AppUsageLimit row that does not exist for Dopamine Fast.
 */
object UsageImpactRouter {
    suspend fun shouldShowForBlockedApp(context: Context, packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val database = AppDatabase.getDatabase(appContext)
            val now = System.currentTimeMillis()

            val timedSession = findActiveTimedSessionForApp(
                context = appContext,
                database = database,
                packageName = packageName,
                nowMillis = now
            )
            if (timedSession != null) {
                UsageInterventionStore.syncFromTimeSession(
                    context = appContext,
                    packageName = packageName,
                    session = timedSession
                )
                return@withContext true
            }

            val limit = database.appUsageLimitDao()
                .getAllStatic()
                .firstOrNull { it.packageName == packageName }
                ?: return@withContext false

            if (!limit.isEnabled || !limit.preventOpeningAfterLimit) return@withContext false
            val mode = limit.lockMode.trim().uppercase()
            if (mode == "PASSWORD" || mode == "WARNING") return@withContext false

            // HARD_BLOCK_NO_PASSWORD is an immediate timed usage-limit block.
            // While its absolute deadline is active, every attempt opens impact
            // metrics anchored at the real createdAt of this configuration.
            if (mode == "TIME") {
                val lockUntil = limit.lockUntilTimestamp ?: return@withContext false
                val blocked = lockUntil > now
                if (blocked) UsageInterventionStore.syncFromLimit(appContext, limit)
                return@withContext blocked
            }

            // For ordinary usage limits the intervention appears only after usage
            // accumulated after activation really reaches the allowance. Usage from
            // before activation on the same day stays outside the new allowance.
            val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return@withContext false
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dayUsageMillis = manager.queryAndAggregateUsageStats(
                startOfDay,
                now
            )[packageName]?.totalTimeInForeground ?: 0L
            val usedMillis = AppUsageLimitActivationUsage.effectiveUsageMillis(
                context = appContext,
                usageStatsManager = manager,
                limit = limit,
                currentDayUsageMillis = dayUsageMillis,
                dayStartMillis = startOfDay,
                nowMillis = now
            )

            val blocked = UsageLimitForegroundPolicy.usedMinutes(usedMillis) >=
                limit.dailyLimitMinutes
            if (blocked) UsageInterventionStore.syncFromLimit(appContext, limit)
            blocked
        }

    private suspend fun findActiveTimedSessionForApp(
        context: Context,
        database: AppDatabase,
        packageName: String,
        nowMillis: Long
    ): BlockSession? {
        val sessionIds = database.sessionAppCrossRefDao().getSessionIdsForApp(packageName)
        if (sessionIds.isEmpty()) return null

        val sessionManager = BlockingSessionManager.getInstance(context)
        for (sessionId in sessionIds) {
            val session = database.blockSessionDao().getActiveSessionById(sessionId) ?: continue
            if (!isTimedSessionCandidate(session, nowMillis)) continue
            if (!sessionManager.isCurrentlyInBlockingWindow(session)) continue
            return session
        }
        return null
    }

    internal fun isTimedSessionCandidate(
        session: BlockSession,
        nowMillis: Long
    ): Boolean = session.isActive &&
        session.sessionType.equals("TIME", ignoreCase = true) &&
        session.startTime <= nowMillis &&
        (session.endTime == null || session.endTime > nowMillis)
}
