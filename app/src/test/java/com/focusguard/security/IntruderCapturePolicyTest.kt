package com.focusguard.security

import com.focusguard.security.IntruderCapturePolicy.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntruderCapturePolicyTest {

    @Test
    fun `only the blocked app unlock captures`() {
        val capturing = Surface.entries.filter(IntruderCapturePolicy::capturesOn)

        assertThat(capturing).containsExactly(Surface.BLOCKED_APP_UNLOCK)
    }

    @Test
    fun `protected app access can stage a selfie immediately`() {
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.BLOCKED_APP_UNLOCK,
                photoCaptureEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `abandoned protected app access keeps staged selfie`() {
        assertThat(
            IntruderCapturePolicy.shouldKeepAttemptPhoto(
                authenticatedSuccessfully = false,
                credentialRejected = false
            )
        ).isTrue()
    }

    @Test
    fun `clean successful authentication discards staged selfie`() {
        assertThat(
            IntruderCapturePolicy.shouldKeepAttemptPhoto(
                authenticatedSuccessfully = true,
                credentialRejected = false
            )
        ).isFalse()
    }

    @Test
    fun `rejected typed credential keeps selfie even after later success`() {
        assertThat(
            IntruderCapturePolicy.shouldKeepAttemptPhoto(
                authenticatedSuccessfully = true,
                credentialRejected = true
            )
        ).isTrue()
    }

    @Test
    fun `FocusGuard own lock screen never captures`() {
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.FOCUSGUARD_APP_LOCK,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `password management never captures`() {
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.PASSWORD_MANAGEMENT,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `usage limit prompt never captures`() {
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.USAGE_LIMIT_UNLOCK,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `the preference switches the whole feature off`() {
        Surface.entries.forEach { surface ->
            assertThat(
                IntruderCapturePolicy.shouldCapture(
                    surface = surface,
                    photoCaptureEnabled = false
                )
            ).isFalse()
        }
    }
}
