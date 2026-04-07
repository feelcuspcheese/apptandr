package com.booking.bot.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data models following TECHNICAL_SPEC.md section 3.
 * All models use @Serializable for JSON persistence in DataStore.
 * 
 * Version 1.2 Enhancements:
 * - Added preferredDates to GeneralSettings.
 * - Added preferredDays and preferredDates to ScheduledRun for per-run independence.
 * - Added Recursion fields (isRecurring, remainingOccurrences, endDateMillis).
 */

/**
 * Museum data class (section 3.2)
 */
@Serializable
data class Museum(
    val name: String,
    val slug: String,
    val museumId: String
)

/**
 * CredentialSet data class (section 3.3)
 */
@Serializable
data class CredentialSet(
    val id: String = UUID.randomUUID().toString(),
    var label: String,
    var username: String,
    var password: String,
    var email: String
)

/**
 * SiteConfig data class (section 3.4)
 */
@Serializable
data class SiteConfig(
    val name: String,
    var baseUrl: String,
    var availabilityEndpoint: String,
    var digital: Boolean = true,
    var physical: Boolean = false,
    var location: String = "0",
    val museums: Map<String, Museum> = emptyMap(),
    val credentials: List<CredentialSet> = emptyList(),
    var defaultCredentialId: String? = null
)

/**
 * AdminConfig data class (section 3.5)
 */
@Serializable
data class AdminConfig(
    var activeSite: String = "spl",
    val sites: Map<String, SiteConfig> = mapOf(
        Pair("spl", SiteConfig(
            name = "SPL",
            baseUrl = "https://spl.libcal.com",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0"
        )),
        Pair("kcls", SiteConfig(
            name = "KCLS",
            baseUrl = "https://rooms.kcls.org",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0"
        ))
    )
)

/**
 * GeneralSettings data class (section 3.6)
 */
@Serializable
data class GeneralSettings(
    var mode: String = "alert",
    var strikeTime: String = "09:00",
    var preferredDays: List<String> = listOf("Monday", "Wednesday", "Friday"),
    var preferredDates: List<String> = emptyList(),
    var ntfyTopic: String = "myappointments",
    var preferredMuseumSlug: String = "",
    var checkWindow: String = Defaults.CHECK_WINDOW,
    var checkInterval: String = Defaults.CHECK_INTERVAL,
    var requestJitter: String = Defaults.REQUEST_JITTER,
    var monthsToCheck: Int = Defaults.MONTHS_TO_CHECK,
    var preWarmOffset: String = Defaults.PRE_WARM_OFFSET,
    var maxWorkers: Int = Defaults.MAX_WORKERS,
    var restCycleChecks: Int = Defaults.REST_CYCLE_CHECKS,
    var restCycleDuration: String = Defaults.REST_CYCLE_DURATION
)

/**
 * ScheduledRun data class (section 3.7)
 * Represents a snapshotted configuration locked to a point in time.
 * 
 * @param isRecurring If true, service will reschedule for tomorrow upon completion.
 * @param remainingOccurrences Number of additional times to run (0 = no limit/controlled by end date).
 * @param endDateMillis Hard stop date for recurring runs.
 */
@Serializable
data class ScheduledRun(
    val id: String = UUID.randomUUID().toString(),
    val siteKey: String,
    val museumSlug: String,
    val credentialId: String?,
    val dropTimeMillis: Long,
    val mode: String,
    val preferredDays: List<String> = emptyList(),
    val preferredDates: List<String> = emptyList(),
    val timezone: String = java.util.TimeZone.getDefault().id,
    // v1.2 Recurring Support
    val isRecurring: Boolean = false,
    val remainingOccurrences: Int = 0,
    val endDateMillis: Long? = null
)

/**
 * AppConfig data class (section 3.8)
 */
@Serializable
data class AppConfig(
    val general: GeneralSettings = GeneralSettings(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: List<ScheduledRun> = emptyList()
)

/**
 * LogEntry data class (section 7)
 */
@Serializable
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)
