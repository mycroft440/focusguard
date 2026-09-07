package com.focusguard.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.monetization.MonetizationPolicy
import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.BiometricOnlyActivationPolicy
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FinalConfigStep(
    sessionType: String,
    authManager: com.focusguard.security.AuthManager,
    sites: List<String>,
    apps: List<String>,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val appUnlockStore = remember(context) { PasswordAppUnlockStore(context) }
    var biometricAvailability by remember(context) {
        mutableStateOf(AppUnlockBiometricAuthenticator.availability(context))
    }
    val biometricAvailable =
        biometricAvailability == AppUnlockBiometricAuthenticator.Availability.AVAILABLE
    var biometricAppUnlockEnabled by remember(authManager) {
        mutableStateOf(authManager.isBiometricAppUnlockEnabled())
    }
    val biometricGateTitle = stringResource(R.string.password_app_unlock_quick_biometric_title)
    val biometricGateDescription =
        stringResource(R.string.password_app_unlock_biometric_rewarded_desc)
    val acceptedPasswordSites = remember(sites) {
        BlockTargetPolicy.acceptedRulesForSessionType(
            BlockTargetPolicy.SESSION_TYPE_PASSWORD,
            sites
        )
    }
    val passwordTargetIds = remember(apps, acceptedPasswordSites) {
        buildList {
            apps.mapNotNull(PasswordAppUnlockStore::targetIdForPackage).forEach(::add)
            acceptedPasswordSites
                .mapNotNull(PasswordAppUnlockStore::targetIdForWebsite)
                .forEach(::add)
        }.distinct()
    }

    var isSaving by remember { mutableStateOf(false) }
    var unlockModeName by rememberSaveable { mutableStateOf<String?>(null) }
    val unlockMode = unlockModeName?.let { modeName ->
        runCatching { PasswordAppUnlockMode.valueOf(modeName) }.getOrNull()
    }
    val biometricEnrollmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        biometricAvailability = AppUnlockBiometricAuthenticator.availability(context)
    }

    // Credenciais são intencionalmente mantidas apenas em memória e nunca no SavedState.
    var unlockPassword by remember { mutableStateOf("") }
    var unlockPasswordConfirmation by remember { mutableStateOf("") }
    var patternCredential by remember { mutableStateOf("") }
    var hidePatternTrace by rememberSaveable { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }

    val launchBiometricRewardedGate: () -> Unit = {
        RewardedGateCoordinator.launch(
            context = context,
            requiredAds = MonetizationPolicy.BIOMETRIC_UNLOCK_REWARDED_ADS,
            title = biometricGateTitle,
            description = biometricGateDescription
        ) {
            authManager.setBiometricAppUnlockEnabled(true)
            biometricAppUnlockEnabled = true
            configError = null
        }
    }

    fun returnToMethodSelection() {
        unlockModeName = null
        unlockPassword = ""
        unlockPasswordConfirmation = ""
        patternCredential = ""
        hidePatternTrace = false
        showPatternDialog = false
        configError = null
    }

    if (showPatternDialog) {
        PatternSetupDialog(
            hideTrace = hidePatternTrace,
            onDismiss = { showPatternDialog = false },
            onPatternSet = {
                patternCredential = it
                configError = null
                showPatternDialog = false
            }
        )
    }

    val patternValid = PasswordAppUnlockStore.isPatternValid(patternCredential)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.final_config_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (unlockMode == null) {
                                onBack()
                            } else {
                                returnToMethodSelection()
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (unlockMode == null) {
                UnlockModeSelectionPage(
                    biometricAvailable = biometricAvailable,
                    onModeSelected = { mode ->
                        unlockModeName = mode.name
                        biometricAppUnlockEnabled = authManager.isBiometricAppUnlockEnabled()
                        configError = null
                    }
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(unlockModeLabelRes(unlockMode)),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )

                        when (unlockMode) {
                            PasswordAppUnlockMode.PASSWORD -> {
                                OutlinedTextField(
                                    value = unlockPassword,
                                    onValueChange = {
                                        unlockPassword = it
                                        configError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(stringResource(R.string.password_app_unlock_password))
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password
                                    )
                                )
                                OutlinedTextField(
                                    value = unlockPasswordConfirmation,
                                    onValueChange = {
                                        unlockPasswordConfirmation = it
                                        configError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.password_app_unlock_password_confirm
                                            )
                                        )
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.password_app_unlock_password_requirement,
                                        PasswordAppUnlockStore.MIN_PASSWORD_LENGTH
                                    ),
                                    color = TextHint,
                                    fontSize = 11.sp
                                )
                            }

                            PasswordAppUnlockMode.PATTERN -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.password_app_unlock_hide_pattern),
                                        modifier = Modifier.weight(1f),
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Switch(
                                        checked = hidePatternTrace,
                                        onCheckedChange = { hidePatternTrace = it }
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showPatternDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(
                                            if (patternValid) {
                                                R.string.password_app_unlock_change_pattern
                                            } else {
                                                R.string.password_app_unlock_create_pattern
                                            }
                                        )
                                    )
                                }
                                if (patternValid) {
                                    Text(
                                        stringResource(R.string.password_app_unlock_pattern_ready),
                                        color = AccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            PasswordAppUnlockMode.BIOMETRIC_ONLY -> {
                                Text(
                                    stringResource(
                                        if (biometricAvailable) {
                                            R.string.password_app_unlock_mode_biometric_only
                                        } else {
                                            R.string.password_app_unlock_biometric_required
                                        }
                                    ),
                                    color = if (biometricAvailable) AccentCyan else DangerRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkBg),
                                    border = BorderStroke(
                                        1.dp,
                                        if (biometricAppUnlockEnabled) {
                                            AccentCyan.copy(alpha = 0.42f)
                                        } else {
                                            TextHint.copy(alpha = 0.22f)
                                        }
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                biometricGateTitle,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                stringResource(
                                                    if (biometricAppUnlockEnabled) {
                                                        R.string.password_app_unlock_quick_biometric_desc
                                                    } else {
                                                        R.string.password_app_unlock_biometric_rewarded_desc
                                                    }
                                                ),
                                                color = TextSecondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Switch(
                                            checked = biometricAppUnlockEnabled,
                                            enabled = biometricAvailable,
                                            onCheckedChange = { enable ->
                                                if (enable) {
                                                    launchBiometricRewardedGate()
                                                } else {
                                                    authManager.setBiometricAppUnlockEnabled(false)
                                                    biometricAppUnlockEnabled = false
                                                }
                                                configError = null
                                            }
                                        )
                                    }
                                }

                                if (!biometricAvailable) {
                                    Text(
                                        stringResource(
                                            R.string.password_app_unlock_biometric_setup_before_activation
                                        ),
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            biometricEnrollmentLauncher.launch(
                                                AppUnlockBiometricAuthenticator
                                                    .createEnrollmentIntent(context)
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.password_app_unlock_biometric_reactivate_action
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        configError?.let { message ->
                            Text(message, color = DangerRed, fontSize = 12.sp)
                        }
                    }
                }

                Button(
                    onClick = {
                        if (!isSaving) {
                            val selectedMode = unlockMode
                            val biometricAvailabilityNow =
                                AppUnlockBiometricAuthenticator.availability(context)
                            biometricAvailability = biometricAvailabilityNow
                            val biometricReadyNow =
                                biometricAvailabilityNow ==
                                    AppUnlockBiometricAuthenticator.Availability.AVAILABLE
                            val biometricAppUnlockEnabledNow =
                                authManager.isBiometricAppUnlockEnabled()
                            biometricAppUnlockEnabled = biometricAppUnlockEnabledNow
                            val biometricOnlyMissingRequirement =
                                BiometricOnlyActivationPolicy.missingRequirement(
                                    androidBiometricAvailable = biometricReadyNow,
                                    appBiometricUnlockEnabled = biometricAppUnlockEnabledNow
                                )
                            val validationError = when (selectedMode) {
                                PasswordAppUnlockMode.PASSWORD -> when {
                                    !PasswordAppUnlockStore.isPasswordValid(unlockPassword) ->
                                        context.getString(
                                            R.string.password_app_unlock_password_invalid,
                                            PasswordAppUnlockStore.MIN_PASSWORD_LENGTH
                                        )

                                    unlockPassword != unlockPasswordConfirmation ->
                                        context.getString(
                                            R.string.password_app_unlock_password_mismatch
                                        )

                                    else -> null
                                }

                                PasswordAppUnlockMode.PATTERN -> if (!patternValid) {
                                    context.getString(
                                        R.string.password_app_unlock_pattern_too_short,
                                        PasswordAppUnlockStore.MIN_PATTERN_POINTS
                                    )
                                } else null

                                PasswordAppUnlockMode.BIOMETRIC_ONLY ->
                                    when (biometricOnlyMissingRequirement) {
                                        BiometricOnlyActivationPolicy.MissingRequirement.ANDROID_BIOMETRIC ->
                                            context.getString(
                                                R.string.password_app_unlock_biometric_required
                                            )

                                        BiometricOnlyActivationPolicy.MissingRequirement.APP_BIOMETRIC_UNLOCK ->
                                            biometricGateDescription

                                        null -> null
                                    }
                            }
                            if (validationError != null) {
                                configError = validationError
                                return@Button
                            }

                            val targetCredential = when (selectedMode) {
                                PasswordAppUnlockMode.PASSWORD -> unlockPassword
                                PasswordAppUnlockMode.PATTERN -> patternCredential
                                PasswordAppUnlockMode.BIOMETRIC_ONLY -> null
                            }

                            val effectiveBiometricEnabled =
                                biometricAppUnlockEnabledNow && biometricReadyNow

                            isSaving = true
                            scope.launch {
                                try {
                                    check(
                                        appUnlockStore.saveForTargets(
                                            targetIds = passwordTargetIds,
                                            mode = selectedMode,
                                            credential = targetCredential,
                                            biometricEnabled = effectiveBiometricEnabled,
                                            hidePatternTrace = hidePatternTrace
                                        )
                                    ) { "Não foi possível salvar o método de desbloqueio" }

                                    try {
                                        sessionManager.startPasswordSession(
                                            isFixed24h = true,
                                            startHour = 0,
                                            endHour = 24,
                                            startMinute = 0,
                                            endMinute = 0,
                                            daysOfWeek = "",
                                            apps = apps,
                                            sites = acceptedPasswordSites.toList()
                                        )
                                    } catch (error: Exception) {
                                        appUnlockStore.clearTargets(passwordTargetIds)
                                        throw error
                                    }

                                    isSaving = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.bloqueio_ativado_com_sucesso),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    onFinish()
                                } catch (cancelled: CancellationException) {
                                    isSaving = false
                                    throw cancelled
                                } catch (error: Exception) {
                                    isSaving = false
                                    FocusGuardLogger.logError(
                                        "FinalConfig",
                                        "Falha ao ativar bloqueio por senha",
                                        error
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.erro_ao_iniciar_sessao),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    enabled = !isSaving && passwordTargetIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        stringResource(R.string.final_config_activate_block),
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockModeSelectionPage(
    biometricAvailable: Boolean,
    onModeSelected: (PasswordAppUnlockMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.password_app_unlock_config_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Text(
                stringResource(R.string.password_app_unlock_choose_type),
                color = TextSecondary,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(2.dp))

            UnlockModeChoiceButton(
                label = stringResource(R.string.password_app_unlock_mode_password),
                onClick = { onModeSelected(PasswordAppUnlockMode.PASSWORD) }
            )
            UnlockModeChoiceButton(
                label = stringResource(R.string.password_app_unlock_mode_pattern),
                onClick = { onModeSelected(PasswordAppUnlockMode.PATTERN) }
            )
            UnlockModeChoiceButton(
                label = stringResource(R.string.password_app_unlock_mode_biometric_only),
                onClick = { onModeSelected(PasswordAppUnlockMode.BIOMETRIC_ONLY) }
            )

            if (!biometricAvailable) {
                Text(
                    stringResource(R.string.password_app_unlock_biometric_unavailable),
                    color = TextHint,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun UnlockModeChoiceButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (enabled) AccentCyan.copy(alpha = 0.42f) else TextHint.copy(alpha = 0.22f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary,
            disabledContentColor = TextHint
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (enabled) AccentCyan else TextHint
            )
        }
    }
}

private fun unlockModeLabelRes(mode: PasswordAppUnlockMode): Int = when (mode) {
    PasswordAppUnlockMode.PASSWORD -> R.string.password_app_unlock_mode_password
    PasswordAppUnlockMode.PATTERN -> R.string.password_app_unlock_mode_pattern
    PasswordAppUnlockMode.BIOMETRIC_ONLY -> R.string.password_app_unlock_mode_biometric_only
}

@Composable
private fun PatternSetupDialog(
    hideTrace: Boolean,
    onDismiss: () -> Unit,
    onPatternSet: (String) -> Unit
) {
    var firstPattern by remember { mutableStateOf<String?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val tooShort = stringResource(
        R.string.password_app_unlock_pattern_too_short,
        PasswordAppUnlockStore.MIN_PATTERN_POINTS
    )
    val mismatch = stringResource(R.string.password_app_unlock_pattern_mismatch)
    val instruction = if (firstPattern == null) {
        stringResource(
            R.string.password_app_unlock_pattern_first,
            PasswordAppUnlockStore.MIN_PATTERN_POINTS
        )
    } else {
        stringResource(R.string.password_app_unlock_pattern_confirm)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_app_unlock_pattern_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    instruction,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                PatternLockInput(
                    modifier = Modifier.fillMaxWidth(),
                    hideTrace = hideTrace,
                    resetKey = resetKey,
                    onPatternComplete = { pattern ->
                        when {
                            !PasswordAppUnlockStore.isPatternValid(pattern) -> {
                                error = tooShort
                                resetKey++
                            }

                            firstPattern == null -> {
                                firstPattern = pattern
                                error = null
                                resetKey++
                            }

                            firstPattern == pattern -> onPatternSet(pattern)

                            else -> {
                                firstPattern = null
                                error = mismatch
                                resetKey++
                            }
                        }
                    }
                )
                error?.let {
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
