package com.focusguard.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/** Burns the capture time into the pixels of an intruder selfie before it is retained. */
internal object IntruderPhotoTimestampWriter {

    private const val TAG = "IntruderTimestamp"
    private const val JPEG_QUALITY = 95
    private const val TIMESTAMP_PATTERN = "dd/MM/yyyy HH:mm:ss"

    internal data class OverlayLayout(
        val top: Float,
        val baseline: Float,
        val padding: Float,
        val textSize: Float
    )

    /**
     * Rewrites [photo] only after a complete stamped JPEG has been produced.
     * A processing failure deliberately leaves the original evidence untouched.
     */
    fun stamp(photo: File, capturedAtMillis: Long): Boolean {
        if (!photo.isFile) return false

        var decoded: Bitmap? = null
        var upright: Bitmap? = null
        var mutable: Bitmap? = null
        val temporary = File(
            photo.parentFile,
            ".${photo.nameWithoutExtension}-timestamp-${System.nanoTime()}.tmp"
        )

        return try {
            val orientation = readOrientation(photo)
            decoded = BitmapFactory.decodeFile(photo.absolutePath)
                ?: return false
            upright = applyExifOrientation(decoded!!, orientation)
            mutable = upright!!.copy(Bitmap.Config.ARGB_8888, true)
                ?: return false

            drawTimestamp(
                bitmap = mutable!!,
                text = formatTimestamp(capturedAtMillis)
            )

            FileOutputStream(temporary).use { output ->
                check(mutable!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "JPEG encoder refused timestamped intruder photo"
                }
                output.fd.sync()
            }

            replaceAfterCompleteWrite(temporary, photo)
            // The gallery sorts private evidence by file time, so preserve the
            // actual capture instant rather than the later post-processing instant.
            runCatching { photo.setLastModified(capturedAtMillis) }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not burn timestamp into ${photo.name}; keeping original", error)
            false
        } finally {
            runCatching { if (temporary.exists()) temporary.delete() }
            mutable?.recycle()
            if (upright !== mutable) upright?.recycle()
            if (decoded !== upright && decoded !== mutable) decoded?.recycle()
        }
    }

    internal fun formatTimestamp(
        capturedAtMillis: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = SimpleDateFormat(TIMESTAMP_PATTERN, locale).apply {
        this.timeZone = timeZone
    }.format(Date(capturedAtMillis))

    /** Draws a high-contrast footer so the timestamp survives both preview and export. */
    internal fun drawTimestamp(bitmap: Bitmap, text: String): OverlayLayout {
        require(bitmap.width > 0 && bitmap.height > 0)
        require(text.isNotBlank())

        val shortEdge = min(bitmap.width, bitmap.height).toFloat()
        val padding = (shortEdge * 0.025f).coerceIn(12f, 40f)
        val textSize = (bitmap.width * 0.045f).coerceIn(24f, 76f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val font = textPaint.fontMetrics
        val textHeight = font.descent - font.ascent
        val barHeight = (textHeight + padding * 2f)
            .coerceAtMost(bitmap.height * 0.25f)
        val top = max(0f, bitmap.height - barHeight)
        val baseline = bitmap.height - padding - font.descent

        Canvas(bitmap).apply {
            drawRect(
                0f,
                top,
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                Paint().apply { color = Color.argb(178, 0, 0, 0) }
            )
            drawText(text, padding, baseline, textPaint)
        }

        return OverlayLayout(
            top = top,
            baseline = baseline,
            padding = padding,
            textSize = textSize
        )
    }

    private fun readOrientation(photo: File): Int = runCatching {
        ExifInterface(photo.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * CameraX JPEGs can keep rotation/mirroring in EXIF. The rewritten JPEG no
     * longer carries that metadata, so rotate the pixels before drawing the footer.
     */
    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return source
        }

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun replaceAfterCompleteWrite(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
