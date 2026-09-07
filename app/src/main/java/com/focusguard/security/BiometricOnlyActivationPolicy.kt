package com.focusguard.security

/**
 * Requirements for creating a BIOMETRIC_ONLY app-protection target.
 *
 * Android enrollment and the in-app rewarded biometric-unlock feature are
 * intentionally separate gates. A biometric-only block must never bypass the
 * rewarded feature just because the device already has a fingerprint enrolled.
 */
object BiometricOnlyActivationPolicy {
    enum class MissingRequirement {
        ANDROID_BIOMETRIC,
        APP_BIOMETRIC_UNLOCK
    }

    fun missingRequirement(
        androidBiometricAvailable: Boolean,
        appBiometricUnlockEnabled: Boolean
    ): MissingRequirement? = when {
        !androidBiometricAvailable -> MissingRequirement.ANDROID_BIOMETRIC
        !appBiometricUnlockEnabled -> MissingRequirement.APP_BIOMETRIC_UNLOCK
        else -> null
    }

    fun canActivate(
        androidBiometricAvailable: Boolean,
        appBiometricUnlockEnabled: Boolean
    ): Boolean = missingRequirement(
        androidBiometricAvailable = androidBiometricAvailable,
        appBiometricUnlockEnabled = appBiometricUnlockEnabled
    ) == null
}
