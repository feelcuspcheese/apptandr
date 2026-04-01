package com.booking.bot.data

import kotlinx.serialization.Serializable

/**
 * Hardcoded defaults following TECHNICAL_SPEC.md section 3.1.
 * These values are never user-editable and represent the authoritative defaults
 * for selectors, form field names, and performance tuning.
 */
@Serializable
object Defaults {
    // Selectors and form field names – do not change
    const val BOOKING_LINK_SELECTOR = "a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available"
    const val USERNAME_FIELD = "username"
    const val PASSWORD_FIELD = "password"
    const val SUBMIT_BUTTON = "submit"
    const val AUTH_ID_SELECTOR = "input[name='auth_id']"
    const val LOGIN_URL_SELECTOR = "input[name='login_url']"
    const val EMAIL_FIELD = "email"
    const val SUCCESS_INDICATOR = "Thank you!"

    // Performance defaults (user-configurable)
    const val CHECK_WINDOW = "60s"
    const val CHECK_INTERVAL = "0.81s"
    const val REQUEST_JITTER = "0.18s"
    const val MONTHS_TO_CHECK = 2
    const val PRE_WARM_OFFSET = "30s"
    const val MAX_WORKERS = 2
    const val REST_CYCLE_CHECKS = 12
    const val REST_CYCLE_DURATION = "3s"
}
