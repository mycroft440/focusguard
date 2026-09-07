package com.focusguard.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null

    // One CameraManager is owned by the PASSWORD attempt controller. Keep a
    // single capture in flight so a burst of accessibility events cannot race
    // CameraX binding/unbinding against itself.
    private val isCapturing = AtomicBoolean(false)

    fun setupAndCaptureSilent(lifecycleOwner: LifecycleOwner, onComplete: (File?) -> Unit) {
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w("CameraManager", "Capture already in progress, skipping")
            onComplete(null)
            return
        }

        val completionDelivered = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        var cameraProvider: ProcessCameraProvider? = null
        var timeoutRunnable: Runnable? = null

        fun finishOnce(file: File?) {
            if (!completionDelivered.compareAndSet(false, true)) {
                // A result arriving after timeout is stale. Never leave an
                // untracked intruder image behind from a callback the caller has
                // already considered failed.
                if (file != null) runCatching { file.delete() }
                return
            }
            timeoutRunnable?.let(timeoutHandler::removeCallbacks)
            runCatching { cameraProvider?.unbindAll() }
            isCapturing.set(false)
            onComplete(file)
        }

        timeoutRunnable = Runnable {
            if (!completionDelivered.get()) {
                Log.w("CameraManager", "Capture timeout reached")
                finishOnce(null)
            }
        }.also { timeoutHandler.postDelayed(it, CAPTURE_TIMEOUT_MILLIS) }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (completionDelivered.get()) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                if (completionDelivered.get()) {
                    runCatching { provider.unbindAll() }
                    return@addListener
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = try {
                    val hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    if (hasFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        Log.w("CameraManager", "No front camera available, trying back camera")
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                } catch (error: Exception) {
                    Log.e("CameraManager", "Camera check failed, falling back to back camera", error)
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                takePhoto { file -> finishOnce(file) }
            } catch (error: Exception) {
                Log.e("CameraManager", "Use case binding failed", error)
                finishOnce(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun takePhoto(onComplete: (File?) -> Unit) {
        val capture = imageCapture ?: run {
            onComplete(null)
            return
        }

        // Intruder photos stay inside the app sandbox. The user can explicitly
        // export a copy later from Intruder Log.
        val photosDir = File(context.filesDir, "IntruderPhotos")
        if (!photosDir.exists() && !photosDir.mkdirs()) {
            Log.e("CameraManager", "Could not create intruder photo directory")
            onComplete(null)
            return
        }
        val photoFile = File(
            photosDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraManager", "Photo capture failed: ${exc.message}", exc)
                    runCatching { if (photoFile.exists()) photoFile.delete() }
                    onComplete(null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraManager", "Photo capture succeeded: ${photoFile.absolutePath}")
                    onComplete(photoFile)
                }
            }
        )
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MILLIS = 5_000L
    }
}
