package com.focusguard.ui.compose.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.BlockingSessionManager.BlockingProtectionUnavailableException
import com.focusguard.security.BlockDurationPolicy
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class TimeBlockConfigPage {
    TERMS,
    SCHEDULE
}

private data class DopamineWeekday(
    val calendarDay: Int,
    @StringRes val labelRes: Int
)

private val DOPAMINE_WEEKDAYS = listOf(
    DopamineWeekday(Calendar.MONDAY, R.string.dopamine_weekday_mon),
    DopamineWeekday(Calendar.TUESDAY, R.string.dopamine_weekday_tue),
    DopamineWeekday(Calendar.WEDNESDAY, R.string.dopamine_weekday_wed),
    DopamineWeekday(Calendar.THURSDAY, R.string.dopamine_weekday_thu),
    DopamineWeekday(Calendar.FRIDAY, R.string.dopamine_weekday_fri),
    DopamineWeekday(Calendar.SATURDAY, R.string.dopamine_weekday_sat),
    DopamineWeekday(Calendar.SUNDAY, R.string.dopamine_weekday_sun)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimeBlockSessionConfigScreen(
    appName: String,
    apps: List<String>,
    sites: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }

    var page by remember { mutableStateOf(TimeBlockConfigPage.TERMS) }
    val availableUnits = remember(apps, sites) {
        BlockDurationPolicy.availableUnits(rules = sites, hasApps = apps.isNotEmpty())
    }
    var durationUnit by remember { mutableStateOf(BlockDurationPolicy.Unit.DAYS) }
    if (durationUnit !in availableUnits) {
        durationUnit = BlockDurationPolicy.Unit.DAYS
    }
    var amountText by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var selectedDays by remember {
        mutableStateOf(DOPAMINE_WEEKDAYS.mapTo(linkedSetOf()) { it.calendarDay }.toSet())
    }
    var pendingProtectionReason by remember {
        mutableStateOf<BlockingProtectionUnavailableException.Reason?>(null)
    }
    var showMasterCredentialSetup by remember { mutableStateOf(false) }

    val duration = BlockDurationPolicy.resolve(durationUnit, amountText.toIntOrNull())
    val selectedDaysSerialized = remember(selectedDays) {
        DOPAMINE_WEEKDAYS
            .filter { it.calendarDay in selectedDays }
            .joinToString(",") { it.calendarDay.toString() }
    }
    val hasTargets = apps.isNotEmpty() || sites.isNotEmpty()
    val canContinue = termsAccepted && hasTargets
    val canSave = duration != null &&
        termsAccepted &&
        selectedDays.isNotEmpty() &&
        hasTargets

    fun navigateBack() {
        if (page == TimeBlockConfigPage.SCHEDULE) {
            page = TimeBlockConfigPage.TERMS
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::navigateBack)

    if (showMasterCredentialSetup) {
        DeactivationCredentialDialog(
            managementLocked = false,
            onDismiss = { showMasterCredentialSetup = false },
            onCredentialChanged = { showMasterCredentialSetup = false }
        )
    }

    pendingProtectionReason?.let { reason ->
        val message = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.blocking_permissions_required_desc
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_required_to_block
            }
        }
        val confirmLabel = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.dopamine_open_permissions
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_create_action
            }
        }

        AlertDialog(
            onDismissRequest = { pendingProtectionReason = null },
            title = { Text(stringResource(R.string.dopamine_requirement_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProtectionReason = null
                        when (reason) {
                            BlockingProtectionUnavailableException
                                .Reason.PROTECTION_PERMISSIONS_REQUIRED ->
                                context.startActivity(
                                    PermissionsActivity.createPendingProtectionIntent(context)
                                )

                            BlockingProtectionUnavailableException
                                .Reason.MASTER_CREDENTIAL_REQUIRED ->
                                showMasterCredentialSetup = true
                        }
                    }
                ) {
                    Text(stringResource(confirmLabel))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtectionReason = null }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dopamine_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            when (page) {
                TimeBlockConfigPage.TERMS -> TimeBlockTermsPage(
                    appName = appName,
                    apps = apps,
                    termsAccepted = termsAccepted,
                    canContinue = canContinue,
                    onTermsAcceptedChange = { termsAccepted = it },
                    onContinue = { page = TimeBlockConfigPage.SCHEDULE },
                    onBack = onBack
                )

                TimeBlockConfigPage.SCHEDULE -> TimeBlockSchedulePage(
                    durationUnit = durationUnit,
                    amountText = amountText,
                    availableUnits = availableUnits,
                    selectedDays = selectedDays,
                    isSaving = isSaving,
                    canSave = canSave,
                    onDurationUnitChange = { durationUnit = it },
                    onAmountChange = { amountText = it },
                    onToggleDay = { day ->
                        selectedDays = if (day in selectedDays) {
                            selectedDays - day
                        } else {
                            selectedDays + day
                        }
                    },
                    onActivate = {
                        if (isSaving) return@TimeBlockSchedulePage
                        isSaving = true
                        scope.launch {
                            try {
                                val resolved = duration ?: run {
                                    isSaving = false
                                    return@launch
                                }
                                sessionManager.startTimeSession(
                                    days = 0,
                                    hours = when (resolved) {
                                        is BlockDurationPolicy.Duration.Finite -> resolved.totalHours
                                        BlockDurationPolicy.Duration.Forever -> 0
                                    },
                                    openEnded = resolved is BlockDurationPolicy.Duration.Forever,
                                    // A recorrência continua sendo de dia inteiro; a separação
                                    // em duas telas é somente de configuração/apresentação.
                                    isFixed24h = false,
                                    startHour = 0,
                                    endHour = 24,
                                    startMinute = 0,
                                    endMinute = 0,
                                    daysOfWeek = selectedDaysSerialized,
                                    apps = apps,
                                    sites = sites
                                )
                                isSaving = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.bloqueio_por_tempo_ativado),
                                    Toast.LENGTH_LONG
                                ).show()
                                onFinish()
                            } catch (cancelled: CancellationException) {
                                isSaving = false
                                throw cancelled
                            } catch (error: BlockingProtectionUnavailableException) {
                                isSaving = false
                                pendingProtectionReason = error.reason
                            } catch (error: Exception) {
                                isSaving = false
                                FocusGuardLogger.logError(
                                    "TimeBlockConfig",
                                    "Falha ao ativar bloqueio por tempo",
                                    error
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.erro_ao_iniciar_sessao),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onBack = { page = TimeBlockConfigPage.TERMS }
                )
            }
        }
    }
}

@Composable
private fun TimeBlockTermsPage(
    appName: String,
    apps: List<String>,
    termsAccepted: Boolean,
    canContinue: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    DopamineHowItWorksCard(
        termsAccepted = termsAccepted,
        onTermsAcceptedChange = onTermsAcceptedChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    SelectedAppsSummary(
        appName = appName,
        apps = apps
    )

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        color = AccentCyan.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = stringResource(R.string.dopamine_simple_mode_info),
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            fontSize = 13.sp
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        enabled = canContinue,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            stringResource(R.string.dopamine_continue_to_schedule),
            color = DarkBg,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_back), color = TextSecondary)
    }
}

@Composable
private fun TimeBlockSchedulePage(
    durationUnit: BlockDurationPolicy.Unit,
    amountText: String,
    availableUnits: List<BlockDurationPolicy.Unit>,
    selectedDays: Set<Int>,
    isSaving: Boolean,
    canSave: Boolean,
    onDurationUnitChange: (BlockDurationPolicy.Unit) -> Unit,
    onAmountChange: (String) -> Unit,
    onToggleDay: (Int) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit
) {
    Text(
        text = stringResource(R.string.dopamine_schedule_config_title),
        color = TextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.dopamine_schedule_config_subtitle),
        color = TextSecondary,
        fontSize = 13.sp
    )

    Spacer(modifier = Modifier.height(18.dp))

    DopamineWeekdaySelector(
        selectedDays = selectedDays,
        onToggleDay = onToggleDay
    )

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dopamine_duration_days_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(14.dp))
            BlockDurationPicker(
                unit = durationUnit,
                amountText = amountText,
                onUnitChange = onDurationUnitChange,
                onAmountChange = onAmountChange,
                accent = DangerRed,
                units = availableUnits
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = DangerRed.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (durationUnit == BlockDurationPolicy.Unit.FOREVER) {
                        stringResource(R.string.dopamine_duration_forever_warning)
                    } else {
                        stringResource(R.string.dopamine_warning)
                    },
                    color = DangerRed,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Start
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onActivate,
        enabled = canSave && !isSaving,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            stringResource(R.string.dopamine_activate),
            color = DarkBg,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_back), color = TextSecondary)
    }
}

@Composable
private fun SelectedAppsSummary(
    appName: String,
    apps: List<String>
) {
    val context = LocalContext.current
    val labels = remember(apps, context) {
        apps.associateWith { packageName -> resolveAppLabel(context, packageName) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.dopamine_selected_apps_count,
                    apps.size,
                    apps.size
                ),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dopamine_schedule_description),
                color = TextSecondary,
                fontSize = 13.sp
            )

            if (apps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    apps.take(8).forEachIndexed { index, packageName ->
                        val fade = when (index) {
                            0, 1, 2, 3 -> 1f
                            4 -> 0.70f
                            5 -> 0.44f
                            6 -> 0.24f
                            else -> 0.12f
                        }
                        FocusGuardAppIcon(
                            packageName = packageName,
                            appName = labels[packageName] ?: packageName,
                            modifier = Modifier
                                .offset(x = (index * 38).dp)
                                .size(44.dp)
                                .alpha(fade),
                            cornerRadius = 11.dp,
                            allowRemoteFallback = true
                        )
                    }
                }
            } else if (appName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_configure_for, appName),
                    color = TextHint,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DopamineHowItWorksCard(
    termsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dopamine_terms_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            listOf(
                R.string.dopamine_terms_intro,
                R.string.dopamine_schedule_terms_how,
                R.string.dopamine_terms_escape
            ).forEach { paragraph ->
                Text(
                    text = stringResource(paragraph),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Text(
                text = stringResource(R.string.dopamine_terms_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTermsAcceptedChange(!termsAccepted) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = termsAccepted,
                    onCheckedChange = onTermsAcceptedChange,
                    colors = CheckboxDefaults.colors(checkedColor = DangerRed)
                )
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(
                    text = stringResource(R.string.dopamine_terms_accept),
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DopamineWeekdaySelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dopamine_weekdays_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dopamine_weekdays_hint),
                color = TextHint,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                DOPAMINE_WEEKDAYS.forEach { weekday ->
                    val selected = weekday.calendarDay in selectedDays
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clickable { onToggleDay(weekday.calendarDay) },
                        color = if (selected) {
                            AccentCyan.copy(alpha = 0.18f)
                        } else {
                            DarkBg
                        },
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) AccentCyan else TextHint.copy(alpha = 0.30f)
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(weekday.labelRes),
                                color = if (selected) AccentCyan else TextHint,
                                fontSize = 11.sp,
                                fontWeight = if (selected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (selectedDays.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_weekdays_required),
                    color = DangerRed,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun resolveAppLabel(context: Context, packageName: String): String {
    val installed = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
    if (!installed.isNullOrBlank()) return installed

    return PredefinedApps.PREVENTIVE_APPS
        .firstOrNull { it.packageName == packageName }
        ?.appName
        ?.takeIf(String::isNotBlank)
        ?: packageName.substringAfterLast('.').ifBlank { packageName }
}
