package com.focusguard.security

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
        if (attempt.captureStarted) return
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
        cameraManager.setupAndCaptureSilent(activity) { file ->
            if (file == null) {
                FocusGuardLogger.log(
                    "IntruderCapture",
                    "Tentativa ${attempt.id}: câmera não produziu arquivo"
                )
                return@setupAndCaptureSilent
            }

            attempt.photo = file
            if (
                !IntruderCapturePolicy.shouldKeepAttemptPhoto(
                    authenticatedSuccessfully = attempt.authenticated,
                    credentialRejected = attempt.credentialRejected
                )
            ) {
                runCatching { file.delete() }
                    .onFailure { error ->
                        FocusGuardLogger.logError(
                            "IntruderCapture",
                            "Falha ao descartar selfie de autenticação bem-sucedida",
                            error
                        )
                    }
                attempt.photo = null
            }
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
            attempt.photo?.let { file ->
                runCatching { file.delete() }
                    .onFailure { error ->
                        FocusGuardLogger.logError(
                            "IntruderCapture",
                            "Falha ao descartar selfie de autenticação bem-sucedida",
                            error
                        )
                    }
            }
            attempt.photo = null
        }
    }
}
