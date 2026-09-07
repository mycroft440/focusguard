package com.focusguard.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.WebsiteBlocker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ephemeral authorization for targets protected by a PASSWORD session.
 *
 * A correct target credential never edits/deletes the configured block. App
 * grants cover exactly one foreground visit. Website grants cover the current
 * visit to the matching rule and are revoked when URL matching observes
 * navigation away; a bounded timeout is a fail-closed fallback if no further
 * browser event arrives.
 */
object PasswordTargetAccessGrant {
    private const val APP_OPEN_TIMEOUT_MILLIS = 15_000L
    private const val APP_POLL_MILLIS = 200L
    private const val EVENT_LOOKBACK_MILLIS = 30_000L
    private const val WEBSITE_GRANT_TIMEOUT_MILLIS = 5 * 60_000L
    private const val INTERNAL_ACTIVITY_HANDOFF_WINDOW_MILLIS = 2_000L
    private const val POST_EXIT_ECHO_SUPPRESSION_MILLIS = 4_000L
    private const val POST_EXIT_USAGE_LOOKBACK_MILLIS = 1_500L

    internal data class AppVisitObservation(
        val latestForegroundPackage: String?,
        val latestTargetForegroundAt: Long,
        val latestNonTargetForegroundAt: Long,
        val latestTargetPackageBackgroundAt: Long,
        val latestTargetStoppedAt: Long,
        val latestTargetForegroundClassName: String? = null,
        val latestTargetBackgroundClassName: String? = null,
        val latestTargetStoppedClassName: String? = null
    )

    private data class RecentAppExit(
        val markedAtElapsedMillis: Long,
        val queryFromWallClockMillis: Long,
        val lastKnownForegroundPackage: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val grantedPackages = ConcurrentHashMap.newKeySet<String>()
    private val appMonitorJobs = ConcurrentHashMap<String, Job>()
    private val recentAppExits = ConcurrentHashMap<String, RecentAppExit>()
    private val websiteExpiryElapsed = ConcurrentHashMap<String, Long>()
    private val websiteMonitorJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var applicationContext: Context? = null

    fun grantPackage(context: Context, packageName: String) {
        val target = packageName.takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        applicationContext = appContext
        recentAppExits.remove(target)
        grantedPackages.add(target)
        appMonitorJobs.remove(target)?.cancel()

        // Device Owner can suspend the package independently of Accessibility.
        // Temporarily unsuspend only this authenticated target; the normal
        // reconciliation re-suspends it as soon as the one-visit grant ends.
        DeviceOwnerManager.getInstance(appContext).unblockApps(listOf(target))

        appMonitorJobs[target] = scope.launch {
            monitorSingleAppVisit(appContext, target)
        }
    }

    fun isPackageGranted(packageName: String): Boolean =
        packageName.isNotBlank() && packageName in grantedPackages

    /**
     * Accessibility can deliver a final TYPE_WINDOWS_CHANGED event for the app
     * that just went to background after Home, an Android chooser, or another app
     * already owns the foreground. The one-visit grant must end at that boundary,
     * but that stale exit echo must not be mistaken for a brand-new app entry.
     *
     * This guard exists only for packages whose authenticated visit was actually
     * observed leaving the foreground. A real re-entry is never suppressed: the
     * latest UsageEvents foreground owner is checked again and the guard is removed
     * as soon as the protected package itself is foreground.
     */
    fun shouldSuppressPostExitWindow(packageName: String): Boolean {
        val target = packageName.takeIf(String::isNotBlank) ?: return false
        val recentExit = recentAppExits[target] ?: return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedSinceExit = nowElapsed - recentExit.markedAtElapsedMillis
        if (elapsedSinceExit < 0L || elapsedSinceExit > POST_EXIT_ECHO_SUPPRESSION_MILLIS) {
            recentAppExits.remove(target, recentExit)
            return false
        }

        val observedForegroundPackage = observeForegroundAfterExit(target, recentExit)
            ?: recentExit.lastKnownForegroundPackage
        val suppress = shouldSuppressPostExitEcho(
            target = target,
            observedForegroundPackage = observedForegroundPackage,
            elapsedSinceExitMillis = elapsedSinceExit
        )

        // Once UsageEvents confirms that the protected target is foreground again,
        // this is a genuine new visit and must be intercepted immediately.
        if (!suppress && observedForegroundPackage == target) {
            recentAppExits.remove(target, recentExit)
        }
        return suppress
    }

    fun revokePackage(packageName: String?) {
        val target = packageName?.takeIf(String::isNotBlank) ?: return
        recentAppExits.remove(target)
        grantedPackages.remove(target)
        appMonitorJobs.remove(target)?.cancel()
        reconcileProtection()
    }

    fun grantWebsite(context: Context, ruleOrDomain: String) {
        val rule = WebsiteBlocker.normalizeRule(ruleOrDomain).takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        applicationContext = appContext
        websiteExpiryElapsed[rule] = SystemClock.elapsedRealtime() + WEBSITE_GRANT_TIMEOUT_MILLIS
        websiteMonitorJobs.remove(rule)?.cancel()
        websiteMonitorJobs[rule] = scope.launch {
            delay(WEBSITE_GRANT_TIMEOUT_MILLIS)
            revokeWebsiteRule(rule)
        }

        // Rebuild managed-browser URL policy without this authenticated rule.
        scope.launch {
            DeviceOwnerManager.getInstance(appContext).invalidateWebsitePolicyCache()
            BlockingSessionManager.getInstance(appContext).checkAndEnforce()
        }
    }

    fun isWebsiteRuleGranted(ruleOrDomain: String): Boolean {
        val rule = WebsiteBlocker.normalizeRule(ruleOrDomain)
        if (rule.isBlank()) return false
        val expiry = websiteExpiryElapsed[rule] ?: return false
        if (SystemClock.elapsedRealtime() < expiry) return true
        revokeWebsiteRule(rule)
        return false
    }

    /**
     * Called while evaluating the browser's current URL. A grant survives while
     * the browser remains on its rule and is revoked as soon as another URL is
     * observed, restoring protection for the next visit.
     */
    fun onWebsiteCandidateObserved(
        urlOrDomain: String,
        configuredRules: Collection<String>
    ) {
        if (websiteExpiryElapsed.isEmpty()) return
        val grantedSnapshot = websiteExpiryElapsed.keys.toList()
        grantedSnapshot.forEach { grantedRule ->
            if (grantedRule !in WebsiteBlocker.normalizeRules(configuredRules)) return@forEach
            val stillOnGrantedTarget = WebsiteBlocker.matchesRuleIgnoringGrants(
                urlOrDomain = urlOrDomain,
                normalizedRule = grantedRule
            )
            if (!stillOnGrantedTarget) revokeWebsiteRule(grantedRule)
        }
    }

    fun revokeWebsiteRule(ruleOrDomain: String?) {
        val rule = ruleOrDomain?.let(WebsiteBlocker::normalizeRule)?.takeIf(String::isNotBlank)
            ?: return
        val existed = websiteExpiryElapsed.remove(rule) != null
        websiteMonitorJobs.remove(rule)?.cancel()
        if (existed) reconcileProtection(invalidateWebsitePolicy = true)
    }

    fun clear() {
        grantedPackages.clear()
        appMonitorJobs.values.forEach(Job::cancel)
        appMonitorJobs.clear()
        recentAppExits.clear()
        websiteExpiryElapsed.clear()
        websiteMonitorJobs.values.forEach(Job::cancel)
        websiteMonitorJobs.clear()
    }

    internal fun grantedWebsiteRulesSnapshot(): Set<String> =
        websiteExpiryElapsed.keys.filterTo(linkedSetOf(), ::isWebsiteRuleGranted)

    internal fun shouldSuppressPostExitEcho(
        target: String,
        observedForegroundPackage: String?,
        elapsedSinceExitMillis: Long
    ): Boolean {
        if (target.isBlank() ||
            elapsedSinceExitMillis < 0L ||
            elapsedSinceExitMillis > POST_EXIT_ECHO_SUPPRESSION_MILLIS
        ) {
            return false
        }
        return !observedForegroundPackage.isNullOrBlank() &&
            observedForegroundPackage != target
    }

    /**
     * Pure one-visit policy used by the monitor and unit tests.
     *
     * A visit belongs to the application package, while UsageEvents also carries
     * Activity class names. That extra component identity lets us distinguish a
     * browser's internal Activity handoff (BrowserActivity -> HistoryActivity) from
     * leaving and reopening the same Activity. We therefore keep legitimate
     * History/Downloads/Settings navigation without weakening the one-visit rule.
     */
    internal fun shouldRevokeAppGrant(
        target: String,
        targetSeenForeground: Boolean,
        visitStartedAt: Long,
        observation: AppVisitObservation
    ): Boolean {
        if (!targetSeenForeground || target.isBlank() || visitStartedAt == Long.MIN_VALUE) {
            return false
        }

        // Seeing any other package in foreground after this visit started is a
        // definitive boundary, even if the target was reopened before the next poll.
        if (observation.latestNonTargetForegroundAt > visitStartedAt) return true

        if (observation.latestTargetPackageBackgroundAt > visitStartedAt) {
            val internalHandoff = isInternalTargetActivityHandoff(
                target = target,
                observation = observation,
                exitAt = observation.latestTargetPackageBackgroundAt,
                exitClassName = observation.latestTargetBackgroundClassName
            )
            if (!internalHandoff) return true
        }

        if (observation.latestTargetStoppedAt > visitStartedAt) {
            val newerTargetForeground =
                observation.latestTargetForegroundAt > observation.latestTargetStoppedAt
            val internalHandoff = isInternalTargetActivityHandoff(
                target = target,
                observation = observation,
                exitAt = observation.latestTargetStoppedAt,
                exitClassName = observation.latestTargetStoppedClassName
            )
            if (!newerTargetForeground && !internalHandoff) return true
        }

        val foreground = observation.latestForegroundPackage
        return !foreground.isNullOrBlank() && foreground != target
    }

    private fun isInternalTargetActivityHandoff(
        target: String,
        observation: AppVisitObservation,
        exitAt: Long,
        exitClassName: String?
    ): Boolean {
        if (observation.latestForegroundPackage != target) return false
        val foregroundAt = observation.latestTargetForegroundAt
        if (foregroundAt == Long.MIN_VALUE || exitAt == Long.MIN_VALUE) return false

        val fromClass = exitClassName?.takeIf(String::isNotBlank) ?: return false
        val toClass = observation.latestTargetForegroundClassName
            ?.takeIf(String::isNotBlank) ?: return false
        if (fromClass == toClass) return false

        val distance = if (foregroundAt >= exitAt) {
            foregroundAt - exitAt
        } else {
            exitAt - foregroundAt
        }
        return distance <= INTERNAL_ACTIVITY_HANDOFF_WINDOW_MILLIS
    }

    private suspend fun monitorSingleAppVisit(context: Context, target: String) {
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return revokePackageWithoutCancellingSelf(target)
            val grantedAtElapsed = SystemClock.elapsedRealtime()
            val grantedAtWallClock = System.currentTimeMillis()
            var targetSeenForeground = false
            var visitStartedAt = Long.MIN_VALUE

            while (target in grantedPackages) {
                val observation = observeAppVisit(
                    manager = usage,
                    target = target,
                    notBeforeMillis = grantedAtWallClock
                )
                if (!targetSeenForeground) {
                    val backgroundAt = observation.latestTargetPackageBackgroundAt
                    val stoppedAt = observation.latestTargetStoppedAt
                    val latestTargetExit = maxOf(backgroundAt, stoppedAt)
                    val latestExitClassName = if (backgroundAt >= stoppedAt) {
                        observation.latestTargetBackgroundClassName
                    } else {
                        observation.latestTargetStoppedClassName
                    }
                    val targetIsCurrentlyForeground =
                        observation.latestForegroundPackage == target &&
                            (
                                observation.latestTargetForegroundAt >= latestTargetExit ||
                                    isInternalTargetActivityHandoff(
                                        target = target,
                                        observation = observation,
                                        exitAt = latestTargetExit,
                                        exitClassName = latestExitClassName
                                    )
                                )
                    if (targetIsCurrentlyForeground) {
                        targetSeenForeground = true
                        visitStartedAt = observation.latestTargetForegroundAt
                    } else if (
                        SystemClock.elapsedRealtime() - grantedAtElapsed >= APP_OPEN_TIMEOUT_MILLIS
                    ) {
                        revokePackageWithoutCancellingSelf(target)
                        return
                    }
                } else if (
                    shouldRevokeAppGrant(
                        target = target,
                        targetSeenForeground = targetSeenForeground,
                        visitStartedAt = visitStartedAt,
                        observation = observation
                    )
                ) {
                    revokePackageWithoutCancellingSelf(
                        target = target,
                        exitObservation = observation
                    )
                    return
                }
                delay(APP_POLL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            revokePackageWithoutCancellingSelf(target)
        } finally {
            appMonitorJobs.remove(target)
        }
    }

    private fun observeAppVisit(
        manager: UsageStatsManager,
        target: String,
        notBeforeMillis: Long
    ): AppVisitObservation {
        val end = System.currentTimeMillis()
        val start = maxOf(end - EVENT_LOOKBACK_MILLIS, notBeforeMillis)
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var latestForegroundPackage: String? = null
        var latestForegroundAt = Long.MIN_VALUE
        var latestTargetForegroundAt = Long.MIN_VALUE
        var latestNonTargetForegroundAt = Long.MIN_VALUE
        var latestTargetPackageBackgroundAt = Long.MIN_VALUE
        var latestTargetStoppedAt = Long.MIN_VALUE
        var latestTargetForegroundClassName: String? = null
        var latestTargetBackgroundClassName: String? = null
        var latestTargetStoppedClassName: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < notBeforeMillis) continue

            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (foregroundEvent && event.timeStamp >= latestForegroundAt) {
                latestForegroundAt = event.timeStamp
                latestForegroundPackage = event.packageName
            }
            if (foregroundEvent && event.packageName == target) {
                if (event.timeStamp >= latestTargetForegroundAt) {
                    latestTargetForegroundAt = event.timeStamp
                    latestTargetForegroundClassName = event.className
                }
            } else if (foregroundEvent && event.packageName != target) {
                latestNonTargetForegroundAt = maxOf(latestNonTargetForegroundAt, event.timeStamp)
            }

            if (
                event.packageName == target &&
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND &&
                event.timeStamp >= latestTargetPackageBackgroundAt
            ) {
                latestTargetPackageBackgroundAt = event.timeStamp
                latestTargetBackgroundClassName = event.className
            }
            if (
                event.packageName == target &&
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED &&
                event.timeStamp >= latestTargetStoppedAt
            ) {
                latestTargetStoppedAt = event.timeStamp
                latestTargetStoppedClassName = event.className
            }
        }

        return AppVisitObservation(
            latestForegroundPackage = latestForegroundPackage,
            latestTargetForegroundAt = latestTargetForegroundAt,
            latestNonTargetForegroundAt = latestNonTargetForegroundAt,
            latestTargetPackageBackgroundAt = latestTargetPackageBackgroundAt,
            latestTargetStoppedAt = latestTargetStoppedAt,
            latestTargetForegroundClassName = latestTargetForegroundClassName,
            latestTargetBackgroundClassName = latestTargetBackgroundClassName,
            latestTargetStoppedClassName = latestTargetStoppedClassName
        )
    }

    private fun observeForegroundAfterExit(
        target: String,
        recentExit: RecentAppExit
    ): String? {
        val context = applicationContext ?: return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return runCatching {
            observeAppVisit(
                manager = usage,
                target = target,
                notBeforeMillis = recentExit.queryFromWallClockMillis
            ).latestForegroundPackage
        }.getOrNull()
    }

    private fun revokePackageWithoutCancellingSelf(
        target: String,
        exitObservation: AppVisitObservation? = null
    ) {
        val exitMarker = exitObservation?.let { observation ->
            RecentAppExit(
                markedAtElapsedMillis = SystemClock.elapsedRealtime(),
                queryFromWallClockMillis = (
                    System.currentTimeMillis() - POST_EXIT_USAGE_LOOKBACK_MILLIS
                ).coerceAtLeast(0L),
                lastKnownForegroundPackage = observation.latestForegroundPackage
            )
        }

        // Publish the exit marker before dropping the grant. Accessibility runs on
        // another thread and must never observe the impossible intermediate state
        // "grant gone, no exit marker" while Android is dispatching stale exit events.
        if (exitMarker != null) {
            recentAppExits[target] = exitMarker
        }

        val existed = grantedPackages.remove(target)
        if (!existed) {
            if (exitMarker != null) recentAppExits.remove(target, exitMarker)
            return
        }

        if (exitMarker == null) {
            recentAppExits.remove(target)
        }
        reconcileProtection()
    }

    private fun reconcileProtection(invalidateWebsitePolicy: Boolean = false) {
        val context = applicationContext ?: return
        scope.launch {
            if (invalidateWebsitePolicy) {
                DeviceOwnerManager.getInstance(context).invalidateWebsitePolicyCache()
            }
            BlockingSessionManager.getInstance(context).checkAndEnforce()
        }
    }
}
