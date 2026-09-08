package com.focusguard.service

import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.security.BiometricAppUnlockPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppBlockSurfaceResolverClosureTest {

    @Test
    fun `plain password visit keeps target task for credential return`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isFalse()
    }

    @Test
    fun `plain password resolution allows one authenticated visit`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK,
            closeTargetAfterInterception = false
        )

        assertThat(resolution.allowsPasswordVisit).isTrue()
    }

    @Test
    fun `generic block resolution cannot grant password visit`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
            closeTargetAfterInterception = true
        )

        assertThat(resolution.allowsPasswordVisit).isFalse()
    }

    @Test
    fun `password surface marked for closure fails closed`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK,
            closeTargetAfterInterception = true
        )

        assertThat(resolution.allowsPasswordVisit).isFalse()
    }

    @Test
    fun `password protected usage limit still closes intercepted target`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin =
                    BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isTrue()
    }

    @Test
    fun `active non password usage intervention closes intercepted target`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = null,
                timedOrUsageInterventionBlocksTarget = true,
                dopamineFastBlocksTarget = false
            )
        ).isTrue()
    }

    @Test
    fun `time commitment overlapping password closes instead of preserving visit`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = true
            )
        ).isTrue()
    }

    @Test
    fun `unknown generic origin does not invent target shutdown`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = null,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isFalse()
    }
}
