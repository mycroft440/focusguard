package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.service.AppBlockSurfaceResolver
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val BIOMETRIC_FAILURES_BEFORE_FALLBACK = 2

/**
 * Unlock controls for a PASSWORD session target.
 *
 * The target credential is independent from the master credential. A successful
 * unlock grants a temporary visit and never edits or deletes the PASSWORD block.
 * Cancelling authentication returns control to the owner Activity so the protected
 * target can be closed instead of falling back to a generic block surface.
 * Intruder-camera ownership also stays in that Activity so the whole access
 * attempt, including cancel/Back without a submitted password, can be recorded.
 */
@Composable
internal fun PasswordProtectedTargetUnlockPanel(
    blockedPackage: String?,
    blockedDomain: String?,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit,
    onCredentialRejected: () -> Unit = {},
    onCancelled: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val store = remember(context) { PasswordAppUnlockStore(context) }
    val websiteTargetId = remember(blockedDomain) {
        store.resolveWebsiteTargetId(blockedDomain)
    }
    val websiteRule = remember(websiteTargetId) {
        PasswordAppUnlockStore.websiteRuleFromTargetId(websiteTargetId)
    }
    val targetId = websiteTargetId ?: PasswordAppUnlockStore.targetIdForPackage(blockedPackage)
    var config by remember(targetId) {
        mutableStateOf(store.getTarget(targetId))
    }
    var showCredentialDialog by remember(targetId) { mutableStateOf(false) }
    var showBiometricOffer by remember(targetId) { mutableStateOf(false) }
    var biometricPromptLaunched by remember(targetId) { mutableStateOf(false) }
    var error by remember(targetId) { mutableStateOf<String?>(null) }
    var verifying by remember(targetId) { mutableStateOf(false) }

    val biometricAvailable = activity != null &&
        AppUnlockBiometricAuthenticator.isAvailable(context)
    val failureMessage = stringResource(R.string.password_app_unlock_failed)
    val wrongCredentialMessage = stringResource(R.string.sessions_wrong_password)
    val promptTitle = stringResource(R.string.password_app_unlock_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.password_app_unlock_biometric_prompt_subtitle)
    val cancelLabel = stringResource(R.string.cancel)

    fun revokePendingGrant() {
        if (websiteRule != null) {
            PasswordTargetAccessGrant.revokeWebsiteRule(websiteRule)
        } else {
            PasswordTargetAccessGrant.revokePackage(blockedPackage)
        }
    }

    fun completeUnlock(onInvalid: (() -> Unit)? = null) {
        if (verifying || targetId == null) return
        scope.launch {
            verifying = true
            error = null
            try {
                val origin = sessionManager.credentialUnlockOrigin(
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    strictPomodoroActive = false
                )
                if (origin != BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION) {
                    error = failureMessage
                    onInvalid?.invoke()
                    return@launch
                }

                // Re-resolve ownership after the target credential has already been
                // accepted. This closes the race where a daily allowance expires
                // while the password/biometric UI is open. A configured limit with
                // quota remaining is intentionally invisible here; only a limit or
                // TIME protection that is blocking at this exact instant may take
                // ownership away from PASSWORD.
                if (!blockedPackage.isNullOrBlank()) {
                    val resolution = AppBlockSurfaceResolver(
                        context = context,
                        sessionManager = sessionManager
                    ).resolveAttempt(
                        blockedPackage = blockedPackage,
                        strictPomodoroActive = false
                    )
                    if (!resolution.allowsPasswordVisit) {
                        error = failureMessage
                        onInvalid?.invoke()
                        return@launch
                    }
                } else {
                    // PASSWORD sessions are app-only in current builds. Keep the
                    // legacy website path fail-closed for old databases rather than
                    // weakening a historical overlapping rule.
                    val overview = sessionManager.getBlockOverview()
                    val candidate = blockedDomain ?: websiteRule.orEmpty()
                    val heldByDopamineFast = overview.dopamineFastEntries.any { entry ->
                        entry.isWebsite && WebsiteBlocker.isUrlBlocked(
                            candidate,
                            listOf(entry.identifier)
                        )
                    }
                    val heldByDailyLimit = overview.dailyLimitEntries.any { entry ->
                        entry.isWebsite && WebsiteBlocker.isUrlBlocked(
                            candidate,
                            listOf(entry.identifier)
                        )
                    }
                    if (heldByDopamineFast || heldByDailyLimit) {
                        error = failureMessage
                        onInvalid?.invoke()
                        return@launch
                    }
                }

                if (websiteRule != null) {
                    PasswordTargetAccessGrant.grantWebsite(context, websiteRule)
                } else {
                    val packageName = blockedPackage?.takeIf(String::isNotBlank)
                        ?: run {
                            error = failureMessage
                            onInvalid?.invoke()
                            return@launch
                        }
                    PasswordTargetAccessGrant.grantPackage(context, packageName)
                }
                showCredentialDialog = false
                onUnlocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                revokePendingGrant()
                error = failureMessage
                onInvalid?.invoke()
            } finally {
                verifying = false
            }
        }
    }

    fun launchBiometric() {
        val host = activity ?: run {
            error = failureMessage
            onCancelled()
            return
        }
        val latest = store.getTarget(targetId) ?: run {
            error = failureMessage
            onCancelled()
            return
        }
        if (!latest.biometricEnabled || !biometricAvailable || verifying) {
            error = failureMessage
            if (!verifying) onCancelled()
            return
        }

        // When password/pattern is available, the negative button becomes an
        // explicit "use password/pattern" route instead of a generic cancel.
        val fallbackLabel = if (latest.hasTypedCredential) {
            host.getString(
                if (latest.mode == PasswordAppUnlockMode.PATTERN) {
                    R.string.password_app_unlock_with_pattern
                } else {
                    R.string.password_app_unlock_with_password
                }
            )
        } else {
            cancelLabel
        }

        AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = promptTitle,
            subtitle = promptSubtitle,
            cancelLabel = fallbackLabel,
            onSuccess = {
                val rechecked = store.getTarget(targetId)
                if (rechecked?.biometricEnabled == true) completeUnlock()
            },
            onError = { message ->
                if (message.isNotBlank()) error = message
            },
            failureThresholdBeforeFallback = if (latest.hasTypedCredential) {
                BIOMETRIC_FAILURES_BEFORE_FALLBACK
            } else {
                0
            },
            onFallbackRequested = {
                if (latest.hasTypedCredential) {
                    error = null
                    showCredentialDialog = true
                }
            },
            onCancelled = {
                if (latest.hasTypedCredential) {
                    // A user pressing "use password/pattern" should land directly
                    // on the alternate credential instead of having to tap again.
                    error = null
                    showCredentialDialog = true
                } else {
                    onCancelled()
                }
            }
        )
    }

    LaunchedEffect(config, targetId) {
        if (targetId == null || config == null) {
            onCancelled()
        }
    }

    LaunchedEffect(config, biometricAvailable) {
        val current = config ?: return@LaunchedEffect
        if (
            current.hasTypedCredential &&
            !current.biometricEnabled &&
            !current.biometricOfferShown &&
            biometricAvailable
        ) {
            showBiometricOffer = true
        }
    }

    // Biometric, when enabled for this target, is always the first unlock surface.
    // Password/pattern remains a fallback after two failed scans or when the user
    // explicitly chooses the negative button in the Android biometric prompt.
    LaunchedEffect(config?.biometricEnabled, config?.mode, biometricAvailable, targetId) {
        val current = config ?: return@LaunchedEffect
        if (
            current.biometricEnabled &&
            biometricAvailable &&
            !biometricPromptLaunched
        ) {
            biometricPromptLaunched = true
            launchBiometric()
        }
    }

    val currentConfig = config ?: return

    Column(modifier = Modifier.fillMaxWidth()) {
        if (currentConfig.biometricEnabled && biometricAvailable) {
            Button(
                onClick = { launchBiometric() },
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = DarkBg)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.password_app_unlock_with_biometric),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (currentConfig.hasTypedCredential) {
            if (currentConfig.biometricEnabled && biometricAvailable) {
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = { showCredentialDialog = true },
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (currentConfig.mode == PasswordAppUnlockMode.PATTERN) {
                            R.string.password_app_unlock_with_pattern
                        } else {
                            R.string.password_app_unlock_with_password
                        }
                    )
                )
            }
        }

        if (
            currentConfig.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY &&
            !biometricAvailable
        ) {
            Text(
                stringResource(R.string.password_app_unlock_biometric_required),
                color = DangerRed,
                fontSize = 13.sp
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = DangerRed, fontSize = 12.sp)
        }
    }

    if (showBiometricOffer) {
        AlertDialog(
            onDismissRequest = {
                store.markBiometricOfferShownForTarget(targetId)
                config = store.getTarget(targetId)
                showBiometricOffer = false
            },
            title = { Text(stringResource(R.string.password_app_unlock_biometric_offer_title)) },
            text = { Text(stringResource(R.string.password_app_unlock_biometric_offer_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (store.setBiometricEnabledForTarget(targetId, true)) {
                            config = store.getTarget(targetId)
                            showBiometricOffer = false
                            biometricPromptLaunched = true
                            launchBiometric()
                        }
                    }
                ) {
                    Text(stringResource(R.string.password_app_unlock_biometric_offer_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        store.markBiometricOfferShownForTarget(targetId)
                        config = store.getTarget(targetId)
                        showBiometricOffer = false
                    }
                ) {
                    Text(stringResource(R.string.password_app_unlock_biometric_offer_not_now))
                }
            }
        )
    }

    if (showCredentialDialog) {
        when (currentConfig.mode) {
            PasswordAppUnlockMode.PASSWORD -> PasswordUnlockDialog(
                verifying = verifying,
                error = error,
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                        onCancelled()
                    }
                },
                onSubmit = { password ->
                    if (store.verifyTarget(targetId, password)) {
                        completeUnlock()
                    } else {
                        error = wrongCredentialMessage
                        onCredentialRejected()
                    }
                }
            )

            PasswordAppUnlockMode.PATTERN -> PatternUnlockDialog(
                hideTrace = currentConfig.hidePatternTrace,
                verifying = verifying,
                error = error,
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                        onCancelled()
                    }
                },
                onSubmit = { pattern, reset ->
                    if (store.verifyTarget(targetId, pattern)) {
                        completeUnlock(onInvalid = reset)
                    } else {
                        error = wrongCredentialMessage
                        onCredentialRejected()
                        reset()
                    }
                }
            )

            PasswordAppUnlockMode.BIOMETRIC_ONLY -> Unit
        }
    }
}

/** Compatibility wrapper for call sites that still have an app-only target. */
@Composable
internal fun PasswordProtectedAppUnlockPanel(
    blockedPackage: String,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit,
    onCredentialRejected: () -> Unit = {},
    onCancelled: () -> Unit = {}
) = PasswordProtectedTargetUnlockPanel(
    blockedPackage = blockedPackage,
    blockedDomain = null,
    authManager = authManager,
    sessionManager = sessionManager,
    onUnlocked = onUnlocked,
    onCredentialRejected = onCredentialRejected,
    onCancelled = onCancelled
)

@Composable
private fun PasswordUnlockDialog(
    verifying: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_notice_unlock_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !verifying,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotBlank()) onSubmit(password) }
                    )
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank() && !verifying
            ) {
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.sessions_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}

@Composable
private fun PatternUnlockDialog(
    hideTrace: Boolean,
    verifying: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, () -> Unit) -> Unit
) {
    var resetKey by remember { mutableIntStateOf(0) }
    val reset: () -> Unit = { resetKey++ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_app_unlock_pattern_title)) },
        text = {
            Column {
                PatternLockInput(
                    hideTrace = hideTrace,
                    enabled = !verifying,
                    resetKey = resetKey,
                    onPatternComplete = { pattern -> onSubmit(pattern, reset) }
                )
                error?.let {
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
