package com.focusguard.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.AppDatabase
import com.focusguard.ui.compose.components.FocusGuardBannerAd
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.usage.UsageInterventionStore
import com.focusguard.usage.UsageInterventionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated metrics surface shown after a non-password timed intervention blocks
 * an app. Two anchored banners remain around the metrics while a third banner is
 * part of the scrollable content at the real end of the page.
 */
class UsageImpactActivity : AppCompatActivity() {
    private var targetPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (targetPackage.isBlank()) {
            finish()
            return
        }

        setContent {
            FocusGuardTheme {
                UsageImpactScreen(
                    packageName = targetPackage,
                    onClose = ::finish
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (nextPackage.isBlank()) {
            finish()
            return
        }
        targetPackage = nextPackage
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "usage_impact_package"

        fun createIntent(context: Context, packageName: String): Intent =
            Intent(context, UsageImpactActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
    }
}

private data class UsageImpactSnapshot(
    val appName: String,
    val beforeMillis: Long,
    val afterMillis: Long,
    val windowMillis: Long,
    val interventionType: UsageInterventionType,
    val dailyLimitMinutes: Int?,
    val endsAt: Long?
)

@Composable
private fun UsageImpactScreen(
    packageName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp <= 760
    val snapshot by produceState<UsageImpactSnapshot?>(initialValue = null, packageName) {
        value = loadUsageImpact(context, packageName)
    }

    val horizontalPadding = if (compact) 16.dp else 20.dp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { FocusGuardBannerAd() },
        bottomBar = { FocusGuardBannerAd() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = horizontalPadding,
                    vertical = if (compact) 10.dp else 14.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Impacto do bloqueio",
                color = TextPrimary,
                fontSize = if (compact) 21.sp else 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Text(
                text = "Compare o uso antes e depois do bloqueio.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

            val data = snapshot
            if (data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 220.dp else 280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(34.dp))
                }
            } else {
                Text(
                    text = data.appName,
                    color = TextPrimary,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Antes",
                        value = formatDuration(data.beforeMillis),
                        compact = compact
                    )
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Depois",
                        value = formatDuration(data.afterMillis),
                        compact = compact
                    )
                }
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                val reduction = if (data.beforeMillis > 0L) {
                    ((1.0 - data.afterMillis.toDouble() / data.beforeMillis.toDouble()) * 100.0)
                        .roundToInt()
                } else null
                Text(
                    text = when {
                        reduction == null -> "Bloqueio ativo"
                        reduction >= 0 -> "Uso reduzido em ${reduction}%"
                        else -> "Uso aumentou em ${-reduction}%"
                    },
                    color = if (reduction == null || reduction >= 0) {
                        SuccessGreen
                    } else {
                        TextSecondary
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 15.sp else 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                Text(
                    text = impactDescription(data),
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 15.sp else 17.sp,
                    maxLines = 3
                )

                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 42.dp else 46.dp)
                ) {
                    Text("Voltar", fontSize = if (compact) 14.sp else 15.sp)
                }
                Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
                FocusGuardBannerAd(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun impactDescription(data: UsageImpactSnapshot): String {
    val period = "Períodos equivalentes de ${formatWindow(data.windowMillis)}."
    return when (data.interventionType) {
        UsageInterventionType.TIME_BLOCK -> {
            val end = data.endsAt?.takeIf { it > System.currentTimeMillis() }
            if (end != null) {
                "$period Bloqueio ativo até ${formatTimestamp(end)}."
            } else {
                "$period Bloqueio por tempo ativo."
            }
        }
        UsageInterventionType.USAGE_LIMIT -> {
            val limit = data.dailyLimitMinutes
            if (limit != null) {
                "$period Limite: $limit min/dia."
            } else {
                "$period Limitador diário ativo."
            }
        }
    }
}

@Composable
private fun UsageCard(
    modifier: Modifier,
    title: String,
    value: String,
    compact: Boolean
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 8.dp else 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                color = TextSecondary,
                fontSize = if (compact) 11.sp else 12.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = TextPrimary,
                fontSize = if (compact) 18.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

private suspend fun loadUsageImpact(
    context: Context,
    packageName: String
): UsageImpactSnapshot = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val limit = AppDatabase.getDatabase(context)
        .appUsageLimitDao()
        .getAllStatic()
        .firstOrNull { it.packageName == packageName }
    val intervention = UsageInterventionStore.readApp(context, packageName)
        ?: limit?.let { UsageInterventionStore.syncFromLimit(context, it) }
    val activation = intervention?.startedAt
        ?.takeIf { it in 1 until now }
        ?: limit?.createdAt?.takeIf { it in 1 until now }
        ?: now
    val elapsed = (now - activation).coerceAtLeast(1L)
    val window = minOf(elapsed, activation)
        .coerceAtMost(MAX_WINDOW_MILLIS)
        .coerceAtLeast(1L)
    val beforeStart = activation - window
    val afterEnd = activation + window
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    val before = manager?.queryAndAggregateUsageStats(beforeStart, activation)
        ?.get(packageName)?.totalTimeInForeground ?: 0L
    val after = manager?.queryAndAggregateUsageStats(activation, afterEnd)
        ?.get(packageName)?.totalTimeInForeground ?: 0L
    val label = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
    val type = intervention?.type ?: if (limit?.lockMode.equals("TIME", true)) {
        UsageInterventionType.TIME_BLOCK
    } else {
        UsageInterventionType.USAGE_LIMIT
    }

    UsageImpactSnapshot(
        appName = label,
        beforeMillis = before,
        afterMillis = after,
        windowMillis = window,
        interventionType = type,
        dailyLimitMinutes = intervention?.dailyLimitMinutes
            ?: limit?.dailyLimitMinutes?.takeIf { it > 0 },
        endsAt = intervention?.endsAt ?: limit?.lockUntilTimestamp
    )
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatWindow(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(1L)
    val minutes = seconds / 60L
    val hours = millis.toDouble() / 3_600_000.0
    return when {
        hours >= 48.0 -> String.format(Locale.getDefault(), "%.1f dias", hours / 24.0)
        hours >= 1.0 -> String.format(Locale.getDefault(), "%.1f horas", hours)
        minutes >= 1L -> "$minutes min"
        else -> "$seconds s"
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val MAX_WINDOW_MILLIS = 7L * DAY_MILLIS
