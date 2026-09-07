package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnPreDraw
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.screens.PasswordProtectedTargetUnlockPanel
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Exclusive authentication surface for PASSWORD-session app targets.
 *
 * This Activity owns target password, pattern, biometric fallback, and the
 * one-visit grant. Generic hard-block UI has no access to any of those states.
 * Cancelling authentication exits to Home so the protected app is no longer
 * visible behind the authentication surface.
 */
@AndroidEntryPoint
class PasswordUnlockActivity : AppCompatActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    private var blockedPackage: String? = null
    private var noticeDrawn = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L
    private var blockAttemptId = 0L
    private var authenticationReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })
        showPasswordUnlock(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPasswordUnlock(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        acknowledgePendingNoticeIfPresented()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) acknowledgePendingNoticeIfPresented()
    }

    private fun showPasswordUnlock(sourceIntent: Intent) {
        val packageName = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
        )?.takeIf(String::isNotBlank)
        val curtainGeneration = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        val attemptId = ++blockAttemptId

        blockedPackage = packageName
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L
        noticeDrawn = false
        authenticationReady = curtainGeneration <= 0L

        val targetLabel = resolveAppLabel(packageName)
        setContent {
            FocusGuardTheme {
                PasswordUnlockContent(
                    blockAttemptId = attemptId,
                    blockedPackage = packageName,
                    targetLabel = targetLabel,
                    authenticationReady = authenticationReady,
                    authManager = authManager,
                    blockingSessionManager = blockingSessionManager,
                    onUnlocked = {
                        returnToAuthenticatedTarget(packageName)
                    },
                    onCancelled = ::goHome
                )
            }
        }

        window.decorView.doOnPreDraw {
            noticeDrawn = true
            if (
                pendingCurtainGeneration == curtainGeneration &&
                activityResumed &&
                window.decorView.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            val detectedAt = sourceIntent.getLongExtra(
                BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS,
                0L
            )
            if (detectedAt > 0L) {
                FocusGuardLogger.log(
                    "PasswordUnlock",
                    "Evento→primeiro desenho=${SystemClock.uptimeMillis() - detectedAt}ms"
                )
            }
            acknowledgePendingNoticeIfPresented()
        }
        window.decorView.invalidate()
    }

    private fun acknowledgePendingNoticeIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
        if (generation <= 0L) {
            authenticationReady = true
            return false
        }
        val decor = window.decorView
        val ready = SafeSurfaceReadinessPolicy.decide(
            alreadyDrawn = noticeDrawn,
            freshFrameAfterRequest = freshFrameGeneration == generation,
            lifecycleResumed = activityResumed,
            decorShown = decor.isShown,
            windowFocused = windowFocused
        ) == SafeSurfaceReadinessPolicy.Decision.ACK_NOW
        if (!ready) return false

        pendingCurtainGeneration = 0L
        freshFrameGeneration = 0L
        CurtainDestinationReadyCoordinator.notifyReady(generation)

        // The target panel auto-opens BiometricPrompt. Give the accessibility
        // curtain its normal safe-window settle interval first so the system prompt
        // is never born underneath a touch-consuming overlay.
        val acknowledgedAttempt = blockAttemptId
        decor.postDelayed(
            {
                if (
                    acknowledgedAttempt == blockAttemptId &&
                    !isFinishing &&
                    !isDestroyed
                ) {
                    authenticationReady = true
                }
            },
            BlockingAccessibilityService.SAFE_WINDOW_SETTLE_MILLIS + 80L
        )
        return true
    }

    private fun resolveAppLabel(packageName: String?): String? {
        val target = packageName?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val info = packageManager.getApplicationInfo(target, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    /**
     * A successful target credential grants one visit without deleting the block.
     * Bring the real launcher Activity forward explicitly while that grant is live.
     */
    private fun returnToAuthenticatedTarget(packageName: String?) {
        val target = packageName?.takeIf(String::isNotBlank)
        if (target == null) {
            goHome()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(target)
        if (launchIntent == null) {
            FocusGuardLogger.log(
                "PasswordUnlock",
                "App autenticado não possui Activity de launcher: $target"
            )
            goHome()
            return
        }

        val launched = runCatching {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(launchIntent)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PasswordUnlock",
                "Falha ao restaurar app autenticado $target",
                error
            )
        }.isSuccess

        if (launched) finish() else goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }
}

@Composable
private fun PasswordUnlockContent(
    blockAttemptId: Long,
    blockedPackage: String?,
    targetLabel: String?,
    authenticationReady: Boolean,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit,
    onCancelled: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = remember(blockAttemptId, blockedPackage) {
        PasswordAppUnlockStore(context).get(blockedPackage)
    }
    var unlocked by remember(blockAttemptId) { mutableStateOf(false) }
    var biometricAvailability by remember(blockAttemptId) {
        mutableStateOf(AppUnlockBiometricAuthenticator.availability(context))
    }
    val biometricEnrollmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        biometricAvailability = AppUnlockBiometricAuthenticator.availability(context)
    }
    val biometricOnlyNeedsEnrollment =
        config?.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY &&
            biometricAvailability != AppUnlockBiometricAuthenticator.Availability.AVAILABLE

    // A malformed/missing PASSWORD target must not strand the user on a dead
    // authentication screen and must never fall through to the generic block UI.
    LaunchedEffect(blockAttemptId, blockedPackage, config) {
        if (blockedPackage.isNullOrBlank() || config == null) {
            onCancelled()
        }
    }

    // Re-check after the opaque curtain hands control to this Activity. This
    // closes the race where the user removes the enrolled biometric after the
    // block was configured but before the next protected-app attempt.
    LaunchedEffect(blockAttemptId, authenticationReady) {
        if (authenticationReady) {
            biometricAvailability = AppUnlockBiometricAuthenticator.availability(context)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = AccentCyan.copy(alpha = 0.1f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(100.dp),
                border = BorderStroke(2.dp, AccentCyan)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = AccentCyan
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.password_unlock_screen_title),
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = targetLabel ?: blockedPackage.orEmpty(),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.password_unlock_screen_subtitle),
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            when {
                unlocked -> {
                    Surface(
                        color = SuccessGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SuccessGreen)
                    ) {
                        Text(
                            text = stringResource(R.string.block_notice_unlock_success),
                            color = SuccessGreen,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    LaunchedEffect(blockAttemptId) {
                        delay(180L)
                        onUnlocked()
                    }
                }
                blockedPackage.isNullOrBlank() || config == null -> {
                    Text(
                        text = stringResource(R.string.password_unlock_configuration_missing),
                        color = DangerRed,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                !authenticationReady -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.password_unlock_preparing),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                biometricOnlyNeedsEnrollment -> {
                    Text(
                        text = stringResource(
                            R.string.password_app_unlock_biometric_blocked_until_restored
                        ),
                        color = DangerRed,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            biometricEnrollmentLauncher.launch(
                                AppUnlockBiometricAuthenticator.createEnrollmentIntent(context)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.password_app_unlock_biometric_reactivate_action
                            )
                        )
                    }
                }
                else -> {
                    PasswordProtectedTargetUnlockPanel(
                        blockedPackage = blockedPackage,
                        blockedDomain = null,
                        authManager = authManager,
                        sessionManager = blockingSessionManager,
                        onUnlocked = { unlocked = true },
                        onCancelled = onCancelled
                    )
                }
            }
        }
    }
}
