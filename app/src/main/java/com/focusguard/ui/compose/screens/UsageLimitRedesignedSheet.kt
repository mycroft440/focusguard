@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.focusguard.ui.compose.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentCyanInk
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.UsageLimitBehaviorPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class AppLimitEditorStep {
    DETAILS,
    BLOCK_MODE
}

/**
 * Snapshot transferred between the two editor steps.
 *
 * The text fields deliberately keep their character-by-character state inside
 * [AppLimitDetailsScreen]. Updating this snapshot only when the user continues
 * prevents every key press from invalidating the ModalBottomSheet, header and
 * second-step calculations.
 */
private data class AppLimitDetailsDraft(
    val minutes: Int,
    val duration: Int,
    val durationUnit: UsageLimitBehaviorPolicy.RuleDurationUnit,
    val durationEdited: Boolean
)

/**
 * Two-screen app-limit editor.
 *
 * Screen 1 owns the allowance and the overall rule duration. Screen 2 owns the
 * post-limit behavior exclusively. Keeping the behavior on its own screen makes
 * the two materially different outcomes explicit before the rule is persisted,
 * while preserving the existing callback contract and database semantics.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLimitRedesignedSheet(
    app: UsageLimitAppUi,
    permissionsMissing: Boolean,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean, String, String?, Long?) -> Unit
) {
    val editMode = app.currentLimitMinutes != null
    val now = remember(app.packageName, app.lockUntilTimestamp) {
        System.currentTimeMillis()
    }
    val activeExistingRuleEnd = remember(app.lockUntilTimestamp, now) {
        app.lockUntilTimestamp?.takeIf { it > now }
    }
    val remainingDays = remember(activeExistingRuleEnd, now) {
        activeExistingRuleEnd
            ?.let {
                ((it - now + TimeUnit.DAYS.toMillis(1) - 1L) /
                    TimeUnit.DAYS.toMillis(1)).toInt()
            }
            ?.coerceAtLeast(1)
            ?: 1
    }
    val initialDurationUnit = if (editMode) {
        UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS
    } else {
        UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS
    }

    var step by remember(app.packageName) { mutableStateOf(AppLimitEditorStep.DETAILS) }
    var detailsDraft by remember(
        app.packageName,
        app.currentLimitMinutes,
        app.lockUntilTimestamp
    ) {
        mutableStateOf(
            AppLimitDetailsDraft(
                minutes = app.currentLimitMinutes ?: 0,
                duration = if (editMode) remainingDays else 1,
                durationUnit = initialDurationUnit,
                durationEdited = false
            )
        )
    }
    var behavior by remember(app.packageName, app.lockMode) {
        mutableStateOf(
            if (UsageLimitBehaviorPolicy.isPauseMode(app.lockMode)) {
                UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
            } else {
                UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX
            }
        )
    }

    val calculatedRuleEnd = remember(
        now,
        detailsDraft.duration,
        detailsDraft.durationUnit
    ) {
        UsageLimitBehaviorPolicy.calculateRuleEndMillis(
            nowMillis = now,
            amount = detailsDraft.duration,
            unit = detailsDraft.durationUnit
        )
    }
    val ruleEnd = remember(
        activeExistingRuleEnd,
        detailsDraft.durationEdited,
        calculatedRuleEnd
    ) {
        UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
            existingRuleEndMillis = activeExistingRuleEnd,
            durationEdited = detailsDraft.durationEdited,
            calculatedRuleEndMillis = calculatedRuleEnd
        )
    }
    val canSave = detailsDraft.minutes > 0 &&
        detailsDraft.duration > 0 &&
        ruleEnd != null

    val pauseLabel = stringResource(R.string.limits_pause_30_option)
    val blockTomorrowLabel = stringResource(R.string.limits_block_tomorrow_option)
    val daysLabel = stringResource(R.string.limits_duration_days)
    val weeksLabel = stringResource(R.string.limits_duration_weeks)
    val monthsLabel = stringResource(R.string.limits_duration_months)
    val selectedBehaviorLabel = if (behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) {
        pauseLabel
    } else {
        blockTomorrowLabel
    }
    val durationUnitLabel = when (detailsDraft.durationUnit) {
        UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS -> daysLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS -> weeksLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS -> monthsLabel
    }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = step == AppLimitEditorStep.BLOCK_MODE) {
        step = AppLimitEditorStep.DETAILS
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 36.dp,
                height = 4.dp,
                color = CardBorder
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Keep only an upper bound. A hard minimum fought MainActivity's
                // adjustResize window while the numeric IME was opening/closing,
                // forcing the whole sheet through repeated constraint passes.
                .heightIn(max = 760.dp)
        ) {
            AppLimitSheetHeader(
                packageName = app.packageName,
                appName = app.appName,
                step = step
            )
            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            when (step) {
                AppLimitEditorStep.DETAILS -> {
                    AppLimitDetailsScreen(
                        permissionsMissing = permissionsMissing,
                        initialDraft = detailsDraft,
                        nowMillis = now,
                        activeExistingRuleEnd = activeExistingRuleEnd,
                        editMode = editMode,
                        currentRuleEnd = app.lockUntilTimestamp,
                        onRemove = { onSave(null, false, "NONE", null, null) },
                        onDismiss = onDismiss,
                        onContinue = { draft ->
                            // Commit the local text-field state once, instead of
                            // propagating every keystroke through the whole sheet.
                            detailsDraft = draft
                            focusManager.clearFocus(force = true)
                            step = AppLimitEditorStep.BLOCK_MODE
                        }
                    )
                }

                AppLimitEditorStep.BLOCK_MODE -> {
                    AppLimitBehaviorScreen(
                        behavior = behavior,
                        onBehaviorChange = { behavior = it },
                        minutes = detailsDraft.minutes,
                        duration = detailsDraft.duration,
                        durationUnitLabel = durationUnitLabel,
                        behaviorLabel = selectedBehaviorLabel,
                        canSave = canSave,
                        onBack = { step = AppLimitEditorStep.DETAILS },
                        onSave = {
                            val persistedMode = if (
                                behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
                            ) {
                                UsageLimitBehaviorPolicy.pauseModeFor(app.packageName)
                            } else {
                                UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(app.packageName)
                            }
                            onSave(detailsDraft.minutes, true, persistedMode, null, ruleEnd)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppLimitSheetHeader(
    packageName: String,
    appName: String,
    step: AppLimitEditorStep
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        FocusGuardAppIcon(
            packageName = packageName,
            appName = appName,
            modifier = Modifier.size(42.dp),
            cornerRadius = 12.dp,
            allowRemoteFallback = true
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                appName,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (step == AppLimitEditorStep.DETAILS) "1 / 2" else "2 / 2",
                color = TextHint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AppLimitDetailsScreen(
    permissionsMissing: Boolean,
    initialDraft: AppLimitDetailsDraft,
    nowMillis: Long,
    activeExistingRuleEnd: Long?,
    editMode: Boolean,
    currentRuleEnd: Long?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: (AppLimitDetailsDraft) -> Unit
) {
    var dailyMinutes by remember(initialDraft) {
        mutableStateOf(initialDraft.minutes.takeIf { it > 0 }?.toString().orEmpty())
    }
    var durationAmount by remember(initialDraft) {
        mutableStateOf(initialDraft.duration.takeIf { it > 0 }?.toString().orEmpty())
    }
    var durationUnit by remember(initialDraft) { mutableStateOf(initialDraft.durationUnit) }
    var durationEdited by remember(initialDraft) { mutableStateOf(initialDraft.durationEdited) }

    val enteredMinutes = remember(dailyMinutes) { dailyMinutes.toIntOrNull() ?: 0 }
    val enteredDuration = remember(durationAmount) { durationAmount.toIntOrNull() ?: 0 }
    val calculatedRuleEnd = remember(nowMillis, enteredDuration, durationUnit) {
        UsageLimitBehaviorPolicy.calculateRuleEndMillis(
            nowMillis = nowMillis,
            amount = enteredDuration,
            unit = durationUnit
        )
    }
    val ruleEnd = remember(activeExistingRuleEnd, durationEdited, calculatedRuleEnd) {
        UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
            existingRuleEndMillis = activeExistingRuleEnd,
            durationEdited = durationEdited,
            calculatedRuleEndMillis = calculatedRuleEnd
        )
    }
    val canAdvance = enteredMinutes > 0 && enteredDuration > 0 && ruleEnd != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            PermissionWarning(permissionsMissing)

            UsageLimitDecisionBlock(
                title = stringResource(R.string.limits_daily_max_title)
            ) {
                OutlinedTextField(
                    value = dailyMinutes,
                    onValueChange = { raw ->
                        dailyMinutes = raw.filter(Char::isDigit).take(4)
                    },
                    label = { Text(stringResource(R.string.limits_daily_max_minutes_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        FilterChip(
                            selected = enteredMinutes == minutes,
                            onClick = { dailyMinutes = minutes.toString() },
                            label = { Text("$minutes min") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
                                selectedLabelColor = AccentCyan
                            )
                        )
                    }
                }
            }

            UsageLimitDecisionBlock(
                title = stringResource(R.string.limits_rule_duration_title),
                showDivider = false
            ) {
                OutlinedTextField(
                    value = durationAmount,
                    onValueChange = { raw ->
                        durationEdited = true
                        durationAmount = raw.filter(Char::isDigit).take(3)
                    },
                    label = { Text(stringResource(R.string.limits_duration_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(112.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DurationUnitChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS,
                        label = stringResource(R.string.limits_duration_days),
                        onClick = {
                            durationEdited = true
                            durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS
                        }
                    )
                    DurationUnitChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS,
                        label = stringResource(R.string.limits_duration_weeks),
                        onClick = {
                            durationEdited = true
                            durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS
                        }
                    )
                    DurationUnitChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS,
                        label = stringResource(R.string.limits_duration_months),
                        onClick = {
                            durationEdited = true
                            durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS
                        }
                    )
                }
            }

            if (editMode && currentRuleEnd?.let { it > nowMillis } == true) {
                val formatted = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    .format(Date(currentRuleEnd))
                Text(
                    stringResource(R.string.limits_rule_current_until, formatted),
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            if (editMode) {
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        stringResource(R.string.sessions_remove_item),
                        color = DangerRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        HorizontalDivider(color = CardBorder, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(0.55f)
                    .height(50.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text(stringResource(R.string.pomodoro_cancel_btn))
            }
            Button(
                enabled = canAdvance,
                onClick = {
                    onContinue(
                        AppLimitDetailsDraft(
                            minutes = enteredMinutes,
                            duration = enteredDuration,
                            durationUnit = durationUnit,
                            durationEdited = durationEdited
                        )
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = AccentCyanInk,
                    disabledContainerColor = CardBorder,
                    disabledContentColor = TextHint
                )
            ) {
                Text(
                    stringResource(R.string.limits_continue_to_behavior),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AppLimitBehaviorScreen(
    behavior: String,
    onBehaviorChange: (String) -> Unit,
    minutes: Int,
    duration: Int,
    durationUnitLabel: String,
    behaviorLabel: String,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(
                stringResource(R.string.limits_after_reaching_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))

            BehaviorChoiceCard(
                selected = behavior == UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX,
                title = stringResource(R.string.limits_block_tomorrow_option),
                description = stringResource(R.string.limits_block_tomorrow_desc),
                onClick = {
                    onBehaviorChange(UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX)
                }
            )
            Spacer(Modifier.height(12.dp))
            BehaviorChoiceCard(
                selected = behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX,
                title = stringResource(R.string.limits_pause_30_option),
                description = stringResource(R.string.limits_pause_30_desc),
                onClick = { onBehaviorChange(UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) }
            )

            Spacer(Modifier.height(22.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        R.string.limits_rule_summary,
                        minutes,
                        behaviorLabel,
                        duration,
                        durationUnitLabel
                    ),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        HorizontalDivider(color = CardBorder, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(0.55f)
                    .height(50.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text(stringResource(R.string.action_back))
            }
            Button(
                enabled = canSave,
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = AccentCyanInk,
                    disabledContainerColor = CardBorder,
                    disabledContentColor = TextHint
                )
            ) {
                Text(
                    stringResource(R.string.save),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun UsageLimitDecisionBlock(
    title: String,
    showDivider: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        content()
        if (showDivider) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun DurationUnitChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
            selectedLabelColor = AccentCyan
        )
    )
}

@Composable
private fun BehaviorChoiceCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentCyan.copy(alpha = 0.12f) else DarkCard
        ),
        border = BorderStroke(
            1.dp,
            if (selected) AccentCyan else CardBorder
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = if (selected) AccentCyan else Color.Transparent,
                border = BorderStroke(2.dp, if (selected) AccentCyan else TextHint)
            ) {
                if (selected) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = AccentCyanInk
                        ) {}
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (selected) AccentCyan else TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
