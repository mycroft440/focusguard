package com.focusguard.security

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.focusguard.utils.FocusGuardLogger
import java.io.File

/**
 * Owns the intruder selfie for one PASSWORD-protected app access attempt.
 *
 * The camera is started while the dedicated unlock Activity is still in the
 * foreground instead of waiting for Back/Home/cancel, when CameraX may already
 * have lost an active lifecycle. The file is staged in the private intruder
 * directory and is deleted only when authentication succeeds without any typed
 * credential rejection. An abandoned/cancelled attempt therefore keeps the
 * staged image even when no password was submitted at all.
 */
class IntruderAttemptCaptureController(
    private val activity: FragmentActivity,
    private val authManager: AuthManager,
    private val cameraManager: CameraManager = CameraManager(activity)
) {
    private data class AttemptState(
        val id: Long,
        var captureStarted: Boolean = false,
        var captureAttempts: Int = 0,
        var authenticated: Boolean = false,
        var credentialRejected: Boolean = false,
        var photo: File? = null
    )

    private var currentAttempt: AttemptState? = null

    fun beginAttempt(attemptId: Long) {
        val current = currentAttempt
        if (current?.id == attemptId) return
        currentAttempt = AttemptState(id = attemptId)
    }

    fun startCaptureIfEligible(attemptId: Long) {
        val attempt = currentAttempt?.takeIf { it.id == attemptId } ?: return
        if (attempt.captureStarted || attempt.authenticated || attempt.photo != null) return
        if (
            !IntruderCapturePolicy.shouldCapture(
                surface = IntruderCapturePolicy.Surface.BLOCKED_APP_UNLOCK,
                photoCaptureEnabled = authManager.isPhotoCaptureEnabled()
            )
        ) {
            return
        }

        val cameraGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            FocusGuardLogger.log(
                "IntruderCapture",
                "Selfie habilitada, mas permissão de câmera não está disponível"
            )
            return
        }

        attempt.captureStarted = true
        attempt.captureAttempts += 1
        cameraManager.setupAndCaptureSilent(activity) { file ->
            attempt.captureStarted = false
            if (file == null) {
                FocusGuardLogger.log(
                    "IntruderCapture",
                    "Tentativa ${attempt.id}: câmera não produziu arquivo"
                )
                continuePendingCapture(completedAttempt = attempt, allowSameAttemptRetry = true)
                return@setupAndCaptureSilent
            }

            attempt.photo = file
            if (
                !IntruderCapturePolicy.shouldKeepAttemptPhoto(
                    authenticatedSuccessfully = attempt.authenticated,
                    credentialRejected = attempt.credentialRejected
                )
            ) {
                deleteStagedPhoto(file)
                attempt.photo = null
            }

            // A rapid leave/re-entry may have created a new access attempt while
            // this capture was still holding CameraX. Start that newest pending
            // attempt as soon as the previous camera operation releases.
            continuePendingCapture(completedAttempt = attempt, allowSameAttemptRetry = false)
        }
    }

    fun markCredentialRejected(attemptId: Long) {
        currentAttempt
            ?.takeIf { it.id == attemptId }
            ?.credentialRejected = true
    }

    fun markAuthenticated(attemptId: Long) {
        val attempt = currentAttempt?.takeIf { it.id == attemptId } ?: return
        attempt.authenticated = true
        if (
            !IntruderCapturePolicy.shouldKeepAttemptPhoto(
                authenticatedSuccessfully = true,
                credentialRejected = attempt.credentialRejected
            )
        ) {
            attempt.photo?.let(::deleteStagedPhoto)
            attempt.photo = null
        }
    }

    private fun continuePendingCapture(
        completedAttempt: AttemptState,
        allowSameAttemptRetry: Boolean
    ) {
        val latest = currentAttempt ?: return
        if (latest.authenticated || latest.photo != null) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val shouldRetry = when {
            latest.id != completedAttempt.id -> true
            !allowSameAttemptRetry -> false
            latest.captureAttempts >= MAX_CAPTURE_ATTEMPTS -> false
            else -> true
        }
        if (!shouldRetry) return

        activity.window.decorView.postDelayed(
            {
                val stillLatest = currentAttempt?.takeIf { it.id == latest.id } ?: return@postDelayed
                if (
                    !stillLatest.authenticated &&
                    stillLatest.photo == null &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    startCaptureIfEligible(stillLatest.id)
                }
            },
            CAPTURE_RETRY_DELAY_MILLIS
        )
    }

    private fun deleteStagedPhoto(file: File) {
        val deleted = runCatching { !file.exists() || file.delete() }
            .getOrElse { error ->
                FocusGuardLogger.logError(
                    "IntruderCapture",
                    "Falha ao descartar selfie de autenticação bem-sucedida",
                    error
                )
                false
            }
        if (!deleted) {
            FocusGuardLogger.logError(
                "IntruderCapture",
                "Arquivo de selfie autenticada permaneceu no armazenamento privado"
            )
        }
    }

    private companion object {
        const val MAX_CAPTURE_ATTEMPTS = 3
        const val CAPTURE_RETRY_DELAY_MILLIS = 350L
    }
}
