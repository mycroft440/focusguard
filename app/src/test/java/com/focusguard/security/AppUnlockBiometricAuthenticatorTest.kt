package com.focusguard.security

import androidx.biometric.BiometricManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUnlockBiometricAuthenticatorTest {

    @Test
    fun `strong biometric success allows biometric-only activation`() {
        assertThat(
            AppUnlockBiometricAuthenticator.availabilityFromCanAuthenticateResult(
                BiometricManager.BIOMETRIC_SUCCESS
            )
        ).isEqualTo(AppUnlockBiometricAuthenticator.Availability.AVAILABLE)
    }

    @Test
    fun `no enrolled biometric requires Android enrollment`() {
        assertThat(
            AppUnlockBiometricAuthenticator.availabilityFromCanAuthenticateResult(
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
            )
        ).isEqualTo(AppUnlockBiometricAuthenticator.Availability.ENROLLMENT_REQUIRED)
    }

    @Test
    fun `missing or unavailable hardware never counts as enrolled`() {
        val unavailableResults = listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
        )

        unavailableResults.forEach { result ->
            assertThat(
                AppUnlockBiometricAuthenticator.availabilityFromCanAuthenticateResult(result)
            ).isEqualTo(AppUnlockBiometricAuthenticator.Availability.UNAVAILABLE)
        }
    }
}
