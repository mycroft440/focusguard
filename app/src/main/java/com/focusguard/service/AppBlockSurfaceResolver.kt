package com.focusguard.service

import android.content.Context
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.usage.UsageImpactRouter

/**
 * Resolves the owner of an app interception before any blocking UI is rendered.
 *
 * The Accessibility service still shares a fast aggregate set of blocked package
 * names, but that set is never treated as the reason for the block here. The
 * responsible protection is re-resolved from its own state so PASSWORD can have
 * a dedicated authentication Activity without weakening stronger protections.
 */
internal class AppBlockSurfaceResolver(
    context: Context,
    private val sessionManager: BlockingSessionManager
) {
    data class Resolution(
        val surface: AppBlockSurfacePolicy.Surface,
        /**
         * TIME commitments and active usage limits own a closed target: after the
         * interception succeeds the target Activity/task must not be preserved for
         * a later visit. A plain PASSWORD session is the opposite because its
         * one-visit grant intentionally returns to the same target task.
         */
        val closeTargetAfterInterception: Boolean
    ) {
        /** True only while PASSWORD is still the dominant owner of this attempt. */
        val allowsPasswordVisit: Boolean
            get() = surface == AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK &&
                !closeTargetAfterInterception
    }

    private val appContext = context.applicationContext

    suspend fun resolve(
        blockedPackage: String?,
        strictPomodoroActive: Boolean
    ): AppBlockSurfacePolicy.Surface = resolveAttempt(
        blockedPackage = blockedPackage,
        strictPomodoroActive = strictPomodoroActive
    ).surface

    suspend fun resolveAttempt(
        blockedPackage: String?,
        strictPomodoroActive: Boolean
    ): Resolution {
        val packageName = blockedPackage?.takeIf(String::isNotBlank)
            ?: return Resolution(
                surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
                closeTargetAfterInterception = false
            )

        if (strictPomodoroActive) {
            return Resolution(
                surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
                closeTargetAfterInterception = false
            )
        }

        // This lookup gives password-protected daily limits precedence over a
        // PASSWORD session for the same package. Only PASSWORD_SESSION may enter
        // the target-credential Activity.
        val credentialOrigin = sessionManager.credentialUnlockOrigin(
            blockedPackage = packageName,
            blockedDomain = null,
            strictPomodoroActive = false
        )

        // This is the live, stateful check. It only reports a TIME session while
        // its current schedule window is active, and it only reports a daily limit
        // after the allowance is exhausted while its selected post-limit behavior
        // is actually blocking now. A configured-but-inactive rule must never win.
        val timedOrUsageInterventionBlocksTarget =
            if (
                credentialOrigin ==
                BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
            ) {
                false
            } else {
                UsageImpactRouter.shouldShowForBlockedApp(appContext, packageName)
            }

        if (credentialOrigin != BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION) {
            return Resolution(
                surface = AppBlockSurfacePolicy.decide(
                    AppBlockSurfacePolicy.Facts(
                        strictPomodoro = false,
                        focusModeBlocksTarget = false,
                        dopamineFastBlocksTarget = false,
                        activeUsageLimitBlocksTarget = false,
                        credentialOrigin = credentialOrigin
                    )
                ),
                closeTargetAfterInterception = shouldCloseTargetAfterInterception(
                    credentialOrigin = credentialOrigin,
                    timedOrUsageInterventionBlocksTarget =
                        timedOrUsageInterventionBlocksTarget,
                    dopamineFastBlocksTarget = false
                )
            )
        }

        val focusModeBlocksTarget = FocusModeStore.readSession(appContext)
            ?.takeIf { it.isActive() }
            ?.blockedPackages
            ?.contains(packageName) == true

        // TIME no longer consults BlockOverview here. The overview is an inventory
        // of configured rules and can legitimately contain a recurring TIME rule
        // outside its current blocking window. UsageImpactRouter above already
        // resolves the live TIME owner and therefore avoids a false permanent block.
        val activeTimedOrUsageBlock = timedOrUsageInterventionBlocksTarget

        return Resolution(
            surface = AppBlockSurfacePolicy.decide(
                AppBlockSurfacePolicy.Facts(
                    strictPomodoro = false,
                    focusModeBlocksTarget = focusModeBlocksTarget,
                    dopamineFastBlocksTarget = false,
                    activeUsageLimitBlocksTarget = activeTimedOrUsageBlock,
                    credentialOrigin = credentialOrigin
                )
            ),
            closeTargetAfterInterception = shouldCloseTargetAfterInterception(
                credentialOrigin = credentialOrigin,
                timedOrUsageInterventionBlocksTarget = activeTimedOrUsageBlock,
                dopamineFastBlocksTarget = false
            )
        )
    }

    companion object {
        internal fun shouldCloseTargetAfterInterception(
            credentialOrigin: BiometricAppUnlockPolicy.BlockOrigin?,
            timedOrUsageInterventionBlocksTarget: Boolean,
            dopamineFastBlocksTarget: Boolean
        ): Boolean =
            credentialOrigin ==
                BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK ||
                timedOrUsageInterventionBlocksTarget ||
                dopamineFastBlocksTarget
    }
}
