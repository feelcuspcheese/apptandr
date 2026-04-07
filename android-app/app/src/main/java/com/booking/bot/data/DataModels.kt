package com.booking.bot.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data models following TECHNICAL_SPEC.md section 3.
 * All models use @Serializable for JSON persistence in DataStore.
 * 
 * Version 1.3 Enhancements:
 * - Added RunResult for Booking History (Feature 1).
 * - Added isPaused to GeneralSettings for Master Switch (Feature 3).
 * - Added runHistory to AppConfig (Feature 1).
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
    var restCycleDuration: String = Defaults.REST_CYCLE_DURATION,
    // v1.3 Feature 3: Master Switch
    var isPaused: Boolean = false
)

/**
 * RunResult represents the outcome of a finished agent execution.
 * v1.3 Feature 1
 */
@Serializable
data class RunResult(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val siteName: String,
    val museumName: String,
    val mode: String,
    val status: String, // "SUCCESS", "FAILED", "MISSED"
    val message: String
)

/**
 * ScheduledRun represents the Snapshotted configuration.
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
    val scheduledRuns: List<ScheduledRun> = emptyList(),
    // v1.3 Feature 1: History
    val runHistory: List<RunResult> = emptyList()
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
