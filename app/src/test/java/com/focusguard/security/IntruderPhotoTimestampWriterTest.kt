package com.focusguard.security

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntruderPhotoTimestampWriterTest {

    @Test
    fun `timestamp uses capture instant with date and seconds`() {
        val utc = TimeZone.getTimeZone("UTC")
        val capturedAt = Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 8, 12, 34, 56)
        }.timeInMillis

        assertThat(
            IntruderPhotoTimestampWriter.formatTimestamp(
                capturedAtMillis = capturedAt,
                locale = Locale.US,
                timeZone = utc
            )
        ).isEqualTo("08/09/2026 12:34:56")
    }

    @Test
    fun `timestamp footer is burned into bottom of image`() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        val layout = IntruderPhotoTimestampWriter.drawTimestamp(
            bitmap = bitmap,
            text = "08/09/2026 12:34:56"
        )

        assertThat(layout.top).isGreaterThan(0f)
        assertThat(layout.top).isLessThan(600f)
        assertThat(layout.baseline).isGreaterThan(layout.top)
        assertThat(layout.textSize).isGreaterThan(0f)

        // Native Robolectric graphics executes Canvas blending like Android. The
        // footer must therefore alter an originally white pixel away from the text.
        val footerPixel = bitmap.getPixel(4, 596)
        assertThat(Color.red(footerPixel)).isLessThan(255)
        assertThat(Color.green(footerPixel)).isLessThan(255)
        assertThat(Color.blue(footerPixel)).isLessThan(255)

        bitmap.recycle()
    }
}
