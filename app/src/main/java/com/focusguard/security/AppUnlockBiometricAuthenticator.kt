package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometria usada exclusivamente para abrir apps protegidos.
 *
 * Usa BIOMETRIC_STRONG sem DEVICE_CREDENTIAL. Assim, escolher "somente digital"
 * não permite que o PIN/padrão/senha do próprio aparelho substitua a biometria.
 */
object AppUnlockBiometricAuthenticator {

    enum class Availability {
        AVAILABLE,
        ENROLLMENT_REQUIRED,
        UNAVAILABLE
    }

    fun availability(context: Context): Availability {
        val result = BiometricManager.from(context.applicationContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return availabilityFromCanAuthenticateResult(result)
    }

    fun isAvailable(context: Context): Boolean = availability(context) == Availability.AVAILABLE

    internal fun availabilityFromCanAuthenticateResult(result: Int): Availability = when (result) {
        BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.ENROLLMENT_REQUIRED
        else -> Availability.UNAVAILABLE
    }

    /**
     * Returns the narrowest Android enrollment surface available on this device.
     * Android 11+ receives the strong-biometric requirement explicitly. Older
     * versions fall back to the fingerprint enrollment action and, on OEMs that
     * do not expose it, to the general security settings screen.
     */
    fun createEnrollmentIntent(context: Context): Intent {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                    )
                )
            }
            add(Intent("android.settings.FINGERPRINT_ENROLL"))
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }

        return candidates.firstOrNull { intent ->
            runCatching { intent.resolveActivity(context.packageManager) != null }
                .getOrDefault(false)
        } ?: Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

    /**
     * Opens the strong-biometric prompt.
     *
     * [failureThresholdBeforeFallback] is optional so legacy callers keep the
     * previous behaviour. Password/pattern protected targets pass a positive
     * threshold: after that many consecutive rejected scans, or after a terminal
     * biometric error such as lockout, the caller can present its typed/drawn
     * fallback immediately.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        failureThresholdBeforeFallback: Int = 0,
        onFallbackRequested: () -> Unit = {},
        onCancelled: () -> Unit = {}
    ) {
        if (!isAvailable(activity)) {
            onError("Biometria forte indisponível neste aparelho")
            if (failureThresholdBeforeFallback > 0) onFallbackRequested()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        var consecutiveFailures = 0
        lateinit var prompt: BiometricPrompt
        prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    consecutiveFailures = 0
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    consecutiveFailures++
                    if (
                        failureThresholdBeforeFallback > 0 &&
                        consecutiveFailures >= failureThresholdBeforeFallback
                    ) {
                        // Cancel this prompt before opening the alternate credential
                        // UI, otherwise both surfaces can race each other on screen.
                        prompt.cancelAuthentication()
                        onFallbackRequested()
                    } else {
                        onError("Biometria não reconhecida")
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    val cancelledByUserOrCaller =
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                    if (cancelledByUserOrCaller) {
                        onCancelled()
                    } else {
                        onError(errString.toString())
                        // Lockout, timeout or another terminal biometric error
                        // must not strand users who configured a password/pattern
                        // fallback for this protected target.
                        if (failureThresholdBeforeFallback > 0) {
                            onFallbackRequested()
                        }
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(cancelLabel)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(promptInfo)
    }
}
