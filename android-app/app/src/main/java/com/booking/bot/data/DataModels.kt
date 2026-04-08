package com.booking.bot.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data models following TECHNICAL_SPEC.md section 3.
 * All models use @Serializable for JSON persistence in DataStore.
 * 
 * Version 1.4 Enhancements:
 * - Added verificationStatus and lastVerifiedMillis to CredentialSet.
 */

@Serializable
data class Museum(
    val name: String,
    val slug: String,
    val museumId: String
)

@Serializable
data class CredentialSet(
    val id: String = UUID.randomUUID().toString(),
    var label: String,
    var username: String,
    var password: String,
    var email: String,
    val verificationStatus: String = "UNTESTED",
    val lastVerifiedMillis: Long = 0L
)

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
    var isPaused: Boolean = false
)

@Serializable
data class RunResult(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val siteName: String,
    val museumName: String,
    val mode: String,
    val status: String,
    val message: String
)

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

@Serializable
data class AppConfig(
    val general: GeneralSettings = GeneralSettings(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: List<ScheduledRun> = emptyList(),
    val runHistory: List<RunResult> = emptyList()
)

@Serializable
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)
