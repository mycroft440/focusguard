package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.service.AppBlockSurfaceResolver
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Thin entrypoint used by Accessibility to route a block attempt.
 *
 * This Activity intentionally renders no blocking UI. The opaque accessibility
 * curtain remains in place while the responsible mechanism is resolved, then the
 * original extras (including the curtain generation handshake) are forwarded to
 * exactly one owner Activity:
 *
 *  - [PasswordUnlockActivity] for a plain PASSWORD-session app target;
 *  - [GenericBlockNoticeActivity] for every non-password/stronger protection.
 *
 * Keeping the router UI-free prevents the generic hard-block screen from ever
 * being shown for a password-protected app, even while Room is being queried.
 */
@AndroidEntryPoint
class BlockNoticeActivity : AppCompatActivity() {

    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    private var routeAttemptId = 0L
    private var routeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onDestroy() {
        routeJob?.cancel()
        routeJob = null
        super.onDestroy()
    }

    private fun route(sourceIntent: Intent) {
        routeJob?.cancel()
        routeJob = null
        val attemptId = ++routeAttemptId

        val strictBlock = sourceIntent.getBooleanExtra(
            BlockingAccessibilityService.EXTRA_STRICT_BLOCK,
            false
        )
        val blockedPackage = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
        )?.takeIf(String::isNotBlank)
        val blockedDomain = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN
        )?.takeIf(String::isNotBlank)

        // PASSWORD sessions are app-only. Website, strict, and malformed payloads
        // never need a database round-trip before reaching their generic owner.
        if (strictBlock || blockedDomain != null || blockedPackage == null) {
            launchDestination(
                sourceIntent = sourceIntent,
                surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
                attemptId = attemptId
            )
            return
        }

        routeJob = lifecycleScope.launch {
            val surface = try {
                AppBlockSurfaceResolver(
                    context = applicationContext,
                    sessionManager = blockingSessionManager
                ).resolve(
                    blockedPackage = blockedPackage,
                    strictPomodoroActive = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An app-routing failure must fail closed without ever inventing a
                // generic owner for a possible PASSWORD target. Sending the user
                // home closes the intercepted app and preserves the invariant that
                // the generic screen never substitutes for password authentication.
                FocusGuardLogger.logError(
                    "BlockRouter",
                    "Falha ao resolver superfície para $blockedPackage; saindo para Home",
                    error
                )
                if (attemptId == routeAttemptId && !isFinishing && !isDestroyed) {
                    goHome()
                }
                return@launch
            }

            launchDestination(sourceIntent, surface, attemptId)
        }
    }

    private fun launchDestination(
        sourceIntent: Intent,
        surface: AppBlockSurfacePolicy.Surface,
        attemptId: Long
    ) {
        if (
            attemptId != routeAttemptId ||
            isFinishing ||
            isDestroyed
        ) return

        val launched = runCatching {
            startActivity(createDestinationIntent(this, sourceIntent, surface))
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "BlockRouter",
                "Falha ao abrir superfície ${surface.name}",
                error
            )
        }.isSuccess

        if (launched) {
            finish()
        } else {
            goHome()
        }
    }

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
        finish()
    }

    companion object {
        internal fun createDestinationIntent(
            context: Context,
            sourceIntent: Intent,
            surface: AppBlockSurfacePolicy.Surface
        ): Intent {
            val destination = when (surface) {
                AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK -> PasswordUnlockActivity::class.java
                AppBlockSurfacePolicy.Surface.GENERIC_BLOCK -> GenericBlockNoticeActivity::class.java
            }
            return Intent(context, destination).apply {
                sourceIntent.extras?.let { putExtras(it) }
                addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }
    }
}
