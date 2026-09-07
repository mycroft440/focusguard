package com.focusguard.ui.compose.screens

import kotlin.OptIn
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.analytics.*
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private data class UsageInsightsData(
    val phoneUsage: PhoneUsageInsights,
    val mostUsedApps: List<AppUsageStat>,
    val mostUsedAverageDays: Int,
    val mostOpenedApps: List<AppAccessStat>,
    val neverUsedApps: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsDashboardScreen(onBack: () -> Unit, showTopBar: Boolean = true) {
    val context = LocalContext.current
    val pm = context.packageManager
    val analytics = remember { AdvancedUsageAnalytics(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var phoneUsage by remember {
        mutableStateOf(PhoneUsageInsights(dailyHistory = emptyList(), periodSummary = null))
    }
    var mostUsedApps by remember { mutableStateOf<List<AppUsageStat>>(emptyList()) }
    var mostUsedAverageDays by remember { mutableIntStateOf(1) }
    var mostOpenedApps by remember { mutableStateOf<List<AppAccessStat>>(emptyList()) }
    var neverUsedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasUsageAccess by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }

    var showAverageForMostUsed by remember { mutableStateOf(false) }
    var expandNeverUsed by remember { mutableStateOf(false) }

    // Recarrega ao voltar das configurações sem manter um CoroutineScope
    // pertencente a uma composição que já saiu da tela.
    var reloadTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                reloadTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadTrigger) {
        isLoading = true
        loadFailed = false

        hasUsageAccess = PermissionUtils.isUsageAccessEnabled(context)
        if (!hasUsageAccess) {
            isLoading = false
            return@LaunchedEffect
        }

        runCancellableInsightsLoad {
            withContext(Dispatchers.IO) {
                val end = System.currentTimeMillis()
                val monthPeriod = UsageInsightsPeriodPolicy.currentMonth(end)
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                val start7Days = cal.timeInMillis

                val calToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startToday = calToday.timeInMillis

                UsageInsightsData(
                    phoneUsage = analytics.getPhoneUsageInsights(),
                    mostUsedApps = MonthlyMostUsedAppsProvider.load(
                        context = context.applicationContext,
                        startTime = monthPeriod.startMillis,
                        endTime = monthPeriod.endMillis
                    ),
                    mostUsedAverageDays = monthPeriod.elapsedDays,
                    mostOpenedApps = analytics.getMostOpenedApps(startToday, end),
                    neverUsedApps = analytics.getNeverUsedApps(start7Days, end)
                )
            }
        }
            .onSuccess { data ->
                phoneUsage = data.phoneUsage
                mostUsedApps = data.mostUsedApps
                mostUsedAverageDays = data.mostUsedAverageDays
                mostOpenedApps = data.mostOpenedApps
                neverUsedApps = data.neverUsedApps
            }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "UsageStatsDashboard",
                    "Falha ao carregar dados de insights",
                    error
                )
                loadFailed = true
            }
        isLoading = false
    }

    // Só pinta fundo quando é dona da tela — o que se reconhece por ela trazer a
    // própria barra de título. Como aba, ela vive dentro do halo que a
    // MainScreen já pinta e não deve pintar outro por cima.
    FocusGuardAmbientBackground(
        modifier = Modifier.fillMaxSize(),
        enabled = showTopBar,
        baseColor = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            topBar = {
                if (showTopBar) {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.dashboard_title),
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp
                            )
                        },
                        navigationIcon = {
                            FocusGuardBackButton(
                                onBack = onBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentCyan)
                    }
                }

                !hasUsageAccess -> {
                    InsightsFallback(
                        modifier = Modifier.padding(padding),
                        title = stringResource(R.string.dashboard_permission_title),
                        message = stringResource(R.string.dashboard_permission_desc),
                        actionLabel = stringResource(R.string.dashboard_open_settings),
                        onAction = { openUsageAccessSettings(context) }
                    )
                }

                loadFailed -> {
                    InsightsFallback(
                        modifier = Modifier.padding(padding),
                        title = stringResource(R.string.dashboard_load_failed_title),
                        message = stringResource(R.string.dashboard_load_failed_desc),
                        actionLabel = stringResource(R.string.dashboard_try_again),
                        onAction = { reloadTrigger++ }
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        item(key = "header_spacer") { Spacer(Modifier.height(8.dp)) }

                        item(key = "phone_usage_chart") {
                            PhoneUsageChartSection(phoneUsage)
                        }

                        item(key = "most_used_apps") {
                            MostUsedAppsSection(
                                apps = mostUsedApps,
                                pm = pm,
                                averageDays = mostUsedAverageDays,
                                showAverage = showAverageForMostUsed,
                                onToggleAverage = { showAverageForMostUsed = it }
                            )
                        }

                        item(key = "most_opened_apps") {
                            MostOpenedAppsSection(
                                apps = mostOpenedApps,
                                pm = pm
                            )
                        }

                        item(key = "never_used_apps") {
                            NeverUsedAppsSection(
                                apps = neverUsedApps,
                                pm = pm,
                                expanded = expandNeverUsed,
                                onToggleExpand = { expandNeverUsed = it }
                            )
                        }

                        item(key = "footer_spacer") { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsFallback(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        FocusCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(16.dp))
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun PhoneUsageChartSection(insights: PhoneUsageInsights) {
    val currentWeek = insights.dailyHistory.takeLast(7)
    val currentAvg = insights.completeDaysAverageMs

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.dashboard_phone_usage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(14.dp))

            if (insights.completeDaysAnalyzed > 0) {
                Text(
                    text = stringResource(
                        R.string.dashboard_daily_average_sentence,
                        formatTime(currentAvg)
                    ),
                    color = AccentCyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 27.sp
                )
            } else {
                Text(
                    text = stringResource(R.string.dashboard_daily_average_no_data),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = stringResource(R.string.dashboard_daily_chart_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            DailyUsageBarChart(currentWeek)

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.dashboard_usage_periods_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            val periodSummary = insights.periodSummary
            if (periodSummary == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.dashboard_period_no_data,
                        MIN_PHONE_USAGE_PATTERN_DAYS
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.dashboard_usage_periods_desc,
                        periodSummary.daysAnalyzed
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                UsagePatternStatement(
                    text = stringResource(
                        R.string.dashboard_most_active_period_sentence,
                        periodSummary.mostUsed.startHour,
                        periodSummary.mostUsed.endHour
                    ),
                    accent = AccentCyan
                )
                Spacer(Modifier.height(10.dp))
                UsagePatternStatement(
                    text = stringResource(
                        R.string.dashboard_least_active_period_sentence,
                        periodSummary.leastUsed.startHour,
                        periodSummary.leastUsed.endHour
                    ),
                    accent = AccentPurple
                )
            }

            if (insights.hourlyProfile.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.dashboard_hourly_profile_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (insights.estimatedSleepWindow == null) {
                            R.string.dashboard_hourly_profile_desc
                        } else {
                            R.string.dashboard_hourly_profile_sleep_desc
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                HourlyUsageProfileChart(
                    hourlyProfile = insights.hourlyProfile,
                    estimatedSleepWindow = insights.estimatedSleepWindow
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            Spacer(Modifier.height(20.dp))
            SleepEstimateSection(insights)
        }
    }
}

@Composable
private fun HourlyUsageProfileChart(
    hourlyProfile: List<PhoneUsageHourAverage>,
    estimatedSleepWindow: EstimatedSleepWindow?
) {
    val maxTimeMs = hourlyProfile.maxOfOrNull(PhoneUsageHourAverage::averageTimeMs)
        ?.coerceAtLeast(60_000L)
        ?: 60_000L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        hourlyProfile.forEach { usage ->
            val isLikelySleep = estimatedSleepWindow?.let { estimate ->
                isHourInsideSleepWindow(usage.hour, estimate)
            } == true
            val actualFraction = usage.averageTimeMs.toFloat() / maxTimeMs.toFloat()
            val visibleFraction = if (usage.averageTimeMs > 0L) {
                actualFraction.coerceIn(0.04f, 1f)
            } else {
                0.012f
            }
            val accent = if (isLikelySleep) AccentPurple else AccentCyan

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (isLikelySleep) {
                            AccentPurple.copy(alpha = 0.08f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        }
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(visibleFraction)
                        .clip(
                            RoundedCornerShape(
                                topStart = 3.dp,
                                topEnd = 3.dp,
                                bottomEnd = 0.dp,
                                bottomStart = 0.dp
                            )
                        )
                        .background(
                            if (usage.averageTimeMs > 0L) {
                                accent
                            } else {
                                accent.copy(alpha = 0.2f)
                            }
                        )
                )
            }
        }
    }
    Spacer(Modifier.height(7.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("00h", "06h", "12h", "18h", "24h").forEach { label ->
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun SleepEstimateSection(insights: PhoneUsageInsights) {
    Text(
        text = stringResource(R.string.dashboard_sleep_title),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))

    val estimate = insights.estimatedSleepWindow
    if (estimate == null) {
        Text(
            text = stringResource(
                R.string.dashboard_sleep_insufficient,
                MIN_SLEEP_PATTERN_NIGHTS,
                insights.sleepNightsAvailable
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    } else {
        val bedtime = localizedClockTime(estimate.bedtimeMinuteOfDay)
        val wakeTime = localizedClockTime(estimate.wakeMinuteOfDay)
        val confidenceLabel = when (estimate.confidence) {
            SleepEstimateConfidence.LOW ->
                stringResource(R.string.dashboard_sleep_confidence_low)

            SleepEstimateConfidence.MEDIUM ->
                stringResource(R.string.dashboard_sleep_confidence_medium)

            SleepEstimateConfidence.HIGH ->
                stringResource(R.string.dashboard_sleep_confidence_high)
        }
        val confidenceColor = when (estimate.confidence) {
            SleepEstimateConfidence.LOW -> MaterialTheme.colorScheme.error
            SleepEstimateConfidence.MEDIUM -> AccentPurple
            SleepEstimateConfidence.HIGH -> AccentCyan
        }
        val nightsLabel = pluralStringResource(
            R.plurals.dashboard_sleep_nights,
            estimate.nightsAnalyzed,
            estimate.nightsAnalyzed
        )

        Text(
            text = stringResource(
                R.string.dashboard_sleep_window_sentence,
                bedtime,
                wakeTime
            ),
            color = AccentPurple,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.dashboard_sleep_duration,
                formatTime(estimate.averageDurationMs)
            ),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(
                R.string.dashboard_sleep_confidence,
                confidenceLabel,
                estimate.confidenceScore,
                nightsLabel
            ),
            color = confidenceColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.dashboard_sleep_disclaimer),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

@Composable
private fun localizedClockTime(minuteOfDay: Int): String {
    val normalizedMinute = ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
    return stringResource(
        R.string.dashboard_clock_time,
        normalizedMinute / 60,
        normalizedMinute % 60
    )
}

private fun isHourInsideSleepWindow(
    hour: Int,
    estimate: EstimatedSleepWindow
): Boolean {
    val hourMidpointMinute = hour * 60 + 30
    val startMinute = estimate.bedtimeMinuteOfDay
    val endMinute = estimate.wakeMinuteOfDay
    return if (startMinute < endMinute) {
        hourMidpointMinute in startMinute until endMinute
    } else {
        hourMidpointMinute >= startMinute || hourMidpointMinute < endMinute
    }
}

@Composable
private fun DailyUsageBarChart(dailyUsage: List<DailyPhoneUsage>) {
    val maxTimeMs = dailyUsage.maxOfOrNull { it.totalTimeMs }
        ?.coerceAtLeast(60_000L)
        ?: 60_000L

    if (dailyUsage.isEmpty()) {
        Text(
            text = stringResource(R.string.dashboard_no_data),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        dailyUsage.forEachIndexed { index, usage ->
            val actualFraction = usage.totalTimeMs.toFloat() / maxTimeMs.toFloat()
            val visibleFraction = if (usage.totalTimeMs > 0L) {
                actualFraction.coerceIn(0.035f, 1f)
            } else {
                0.012f
            }
            val accent = if (index == dailyUsage.lastIndex) AccentPurple else AccentCyan

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatChartTime(usage.totalTimeMs),
                    color = accent,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight(visibleFraction)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 6.dp,
                                    topEnd = 6.dp,
                                    bottomEnd = 0.dp,
                                    bottomStart = 0.dp
                                )
                            )
                            .background(
                                if (usage.totalTimeMs > 0L) {
                                    accent
                                } else {
                                    accent.copy(alpha = 0.2f)
                                }
                            )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = usage.dateLabel.take(3),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun UsagePatternStatement(
    text: String,
    accent: androidx.compose.ui.graphics.Color
) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        color = accent,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun MostUsedAppsSection(
    apps: List<AppUsageStat>,
    pm: PackageManager,
    averageDays: Int = 1,
    showAverage: Boolean,
    onToggleAverage: (Boolean) -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_most_used_month), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dashboard_daily_avg), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Switch(checked = showAverage, onCheckedChange = onToggleAverage, modifier = Modifier.scale(0.8f))
                }
            }

            Spacer(Modifier.height(16.dp))

            val displayList = apps.take(3)
            val divisor = averageDays.coerceAtLeast(1).toLong()

            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { stat ->
                    val timeToDisplay = if (showAverage) stat.timeSpentMs / divisor else stat.timeSpentMs
                    AppUsageRow(stat.packageName, timeToDisplay, pm)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun MostOpenedAppsSection(
    apps: List<AppAccessStat>,
    pm: PackageManager
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dashboard_most_opened_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            val displayList = apps.take(3)

            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_accesses), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { app ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app.packageName, pm, 32)
                        Spacer(Modifier.width(12.dp))
                        Text(getAppName(app.packageName, pm), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            pluralStringResource(
                                R.plurals.dashboard_access_count,
                                app.accessCount,
                                app.accessCount
                            ),
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NeverUsedAppsSection(
    apps: List<String>,
    pm: PackageManager,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dashboard_never_used), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            val displayList = if (expanded) apps else apps.take(3)

            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_inactive), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { pkg ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, pm, 32)
                        Spacer(Modifier.width(12.dp))
                        Text(getAppName(pkg, pm), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }

            if (apps.size > 3) {
                TextButton(onClick = { onToggleExpand(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) stringResource(R.string.dashboard_hide) else stringResource(R.string.dashboard_show_all, apps.size), color = AccentCyan)
                }
            }
        }
    }
}

@Composable
fun AppUsageRow(pkg: String, timeMs: Long, pm: PackageManager) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(pkg, pm, 40)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(getAppName(pkg, pm), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(formatTime(timeMs), color = AccentCyan, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AppIcon(pkg: String, pm: PackageManager, size: Int) {
    FocusGuardAppIcon(
        packageName = pkg,
        appName = getAppName(pkg, pm),
        modifier = Modifier.size(size.dp),
        cornerRadius = (size / 4).dp,
        allowRemoteFallback = true
    )
}

fun getAppName(pkg: String, pm: PackageManager): String {
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) {
        pkg
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "0m"
    if (millis < 60000) return "< 1m"
    val totalMinutes = millis / 1000 / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

private fun formatChartTime(millis: Long): String {
    if (millis <= 0L) return "0m"
    if (millis < 60_000L) return "<1m"
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h\n${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun openUsageAccessSettings(context: Context) {
    val appSettingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching {
        context.startActivity(appSettingsIntent)
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

internal suspend fun <T> runCancellableInsightsLoad(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        // O Compose cancela LaunchedEffect ao trocar de aba ou sair da composição.
        // Esse evento precisa continuar sendo cancelamento, nunca estado de erro.
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
