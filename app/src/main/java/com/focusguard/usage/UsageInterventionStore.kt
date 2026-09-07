package com.focusguard.usage

import android.content.Context
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession

enum class UsageInterventionType { USAGE_LIMIT, TIME_BLOCK }

data class UsageIntervention(
    val packageName: String,
    val type: UsageInterventionType,
    val startedAt: Long,
    val endsAt: Long?,
    val dailyLimitMinutes: Int?
)

object UsageInterventionStore {
    private const val PREFS = "focusguard_usage_interventions"

    @Synchronized
    fun syncFromLimit(context: Context, limit: AppUsageLimit): UsageIntervention {
        val intervention = UsageIntervention(
            packageName = limit.packageName,
            type = if (limit.lockMode.equals("TIME", true)) {
                UsageInterventionType.TIME_BLOCK
            } else {
                UsageInterventionType.USAGE_LIMIT
            },
            startedAt = limit.createdAt.coerceAtLeast(1L),
            endsAt = limit.lockUntilTimestamp,
            dailyLimitMinutes = limit.dailyLimitMinutes.takeIf { it > 0 }
        )
        if (readApp(context, limit.packageName) != intervention) {
            write(context, intervention)
        }
        return intervention
    }

    /**
     * Persists the real start/end window of a TIME block session so the impact
     * screen can compare equivalent usage windows around the moment the timed
     * commitment actually started. This keeps TIME-session metrics independent
     * from the daily-limit table.
     */
    @Synchronized
    fun syncFromTimeSession(
        context: Context,
        packageName: String,
        session: BlockSession
    ): UsageIntervention {
        require(session.sessionType.equals("TIME", ignoreCase = true)) {
            "Only TIME sessions can create a time-block intervention"
        }
        val intervention = UsageIntervention(
            packageName = packageName,
            type = UsageInterventionType.TIME_BLOCK,
            startedAt = session.startTime.coerceAtLeast(1L),
            endsAt = session.endTime,
            dailyLimitMinutes = null
        )
        if (readApp(context, packageName) != intervention) {
            write(context, intervention)
        }
        return intervention
    }

    @Synchronized
    fun readApp(context: Context, packageName: String): UsageIntervention? {
        val p = prefs(context)
        val startedAt = p.getLong("started:$packageName", 0L)
        if (startedAt <= 0L) return null
        val type = runCatching {
            UsageInterventionType.valueOf(p.getString("type:$packageName", "").orEmpty())
        }.getOrNull() ?: return null
        val endsRaw = p.getLong("ends:$packageName", Long.MIN_VALUE)
        val limitRaw = p.getInt("limit:$packageName", -1)
        return UsageIntervention(
            packageName = packageName,
            type = type,
            startedAt = startedAt,
            endsAt = endsRaw.takeUnless { it == Long.MIN_VALUE },
            dailyLimitMinutes = limitRaw.takeIf { it > 0 }
        )
    }

    private fun write(context: Context, value: UsageIntervention) {
        val e = prefs(context).edit()
            .putString("type:${value.packageName}", value.type.name)
            .putLong("started:${value.packageName}", value.startedAt)
        if (value.endsAt == null) e.remove("ends:${value.packageName}")
        else e.putLong("ends:${value.packageName}", value.endsAt)
        if (value.dailyLimitMinutes == null) e.remove("limit:${value.packageName}")
        else e.putInt("limit:${value.packageName}", value.dailyLimitMinutes)
        e.commit()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
