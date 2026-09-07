package com.focusguard.security

/**
 * Decides where an intruder selfie may be staged and when it should be retained.
 *
 * The selfie exists to catch an unsuccessful attempt to enter an app protected by
 * PASSWORD. It is not a general failed-password alarm: FocusGuard's own lock and
 * password-management screens stay outside this feature. The dedicated app-unlock
 * Activity owns the camera for the whole access attempt so Back/Home/cancel can be
 * recorded even when no password was ever submitted.
 */
object IntruderCapturePolicy {

    enum class Surface {
        /** Dedicated unlock surface shown before entering a password-protected app. */
        BLOCKED_APP_UNLOCK,

        /** FocusGuard's own lock screen. */
        FOCUSGUARD_APP_LOCK,

        /** The password management area, reachable only after authenticating. */
        PASSWORD_MANAGEMENT,

        /** Password prompt guarding a usage limit. */
        USAGE_LIMIT_UNLOCK
    }

    /** True only for the surface that guards entry into a protected app. */
    fun capturesOn(surface: Surface): Boolean = surface == Surface.BLOCKED_APP_UNLOCK

    /** Whether this access attempt should stage an intruder photo. */
    fun shouldCapture(
        surface: Surface,
        photoCaptureEnabled: Boolean
    ): Boolean = photoCaptureEnabled && capturesOn(surface)

    /**
     * A staged photo is kept whenever the access did not end in a clean successful
     * authentication. A rejected typed password/pattern remains evidence even if
     * the person later manages to authenticate during the same attempt.
     */
    fun shouldKeepAttemptPhoto(
        authenticatedSuccessfully: Boolean,
        credentialRejected: Boolean
    ): Boolean = credentialRejected || !authenticatedSuccessfully
}
