package com.focusguard.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricOnlyActivationPolicyTest {
    @Test
    fun `android biometric is required before app feature`() {
        assertEquals(
            BiometricOnlyActivationPolicy.MissingRequirement.ANDROID_BIOMETRIC,
            BiometricOnlyActivationPolicy.missingRequirement(
                androidBiometricAvailable = false,
                appBiometricUnlockEnabled = false
            )
        )
    }

    @Test
    fun `rewarded app feature is required even when android biometric exists`() {
        assertEquals(
            BiometricOnlyActivationPolicy.MissingRequirement.APP_BIOMETRIC_UNLOCK,
            BiometricOnlyActivationPolicy.missingRequirement(
                androidBiometricAvailable = true,
                appBiometricUnlockEnabled = false
            )
        )
        assertFalse(
            BiometricOnlyActivationPolicy.canActivate(
                androidBiometricAvailable = true,
                appBiometricUnlockEnabled = false
            )
        )
    }

    @Test
    fun `biometric only can activate only when both gates are ready`() {
        assertNull(
            BiometricOnlyActivationPolicy.missingRequirement(
                androidBiometricAvailable = true,
                appBiometricUnlockEnabled = true
            )
        )
        assertTrue(
            BiometricOnlyActivationPolicy.canActivate(
                androidBiometricAvailable = true,
                appBiometricUnlockEnabled = true
            )
        )
    }
}
