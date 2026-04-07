package com.booking.bot.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data models following TECHNICAL_SPEC.md section 3.
 * All models use @Serializable for JSON persistence in DataStore.
 */

/**
 * Museum data class (section 3.2)
 * @param name Display name shown in UI dropdowns
 * @param slug Unique identifier (used as key in maps, stored in preferences)
 * @param museumId Actual ID used in the library's API endpoint
 */
@Serializable
data class Museum(
    val name: String,
    val slug: String,
    val museumId: String
)

/**
 * CredentialSet data class (section 3.3)
 * Represents a single library card + PIN + email combination.
 * Multiple credential sets can exist per site with one marked as default.
 * @param id UUID-based unique identifier
 * @param label User-friendly name (e.g., "Main Card")
 * @param username Library card number
 * @param password PIN
 * @param email Email address for notifications
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
 * Configuration for a single booking site (e.g., SPL, KCLS).
 * @param name Display name (e.g., "SPL")
 * @param baseUrl Base URL for the site (e.g., "https://spl.libcal.com")
 * @param availabilityEndpoint API endpoint for availability checks
 * @param digital Whether to book digital passes
 * @param physical Whether to book physical passes
 * @param location Location parameter for API calls
 * @param museums Map of slug -> Museum for this site
 * @param credentials List of CredentialSet for this site
 * @param defaultCredentialId ID of the default credential (must exist in credentials or be null)
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
 * Site-specific configuration including multiple sites and their settings.
 * @param activeSite Currently selected site key (e.g., "spl")
 * @param sites Map of site key -> SiteConfig
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
 * User-configurable general settings for the booking agent.
 * @param mode "alert" or "booking"
 * @param strikeTime Time when appointments drop (HH:MM format)
 * @param preferredDays List of days to prefer (e.g., ["Monday", "Wednesday", "Friday"])
 * @param preferredDates List of specific ISO dates to prefer (e.g., ["2024-12-25"])
 * @param ntfyTopic Topic name for ntfy.sh notifications
 * @param preferredMuseumSlug Slug of the preferred museum (stored, not displayed name)
 * @param checkWindow Duration to check for availability (e.g., "60s")
 * @param checkInterval Interval between checks (e.g., "0.81s")
 * @param requestJitter Jitter to add to requests (e.g., "0.18s")
 * @param monthsToCheck Number of months ahead to check
 * @param preWarmOffset Time before strike time to start pre-warming (e.g., "30s")
 * @param maxWorkers Maximum number of concurrent workers
 * @param restCycleChecks Number of checks before resting
 * @param restCycleDuration Duration of rest cycle (e.g., "3s")
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
 * Represents a scheduled booking run at a specific time.
 * @param id UUID-based unique identifier
 * @param siteKey Key of the site in admin.sites
 * @param museumSlug Slug of the museum in that site's museums
 * @param credentialId ID of the credential to use (null = use site's default)
 * @param dropTimeMillis Absolute time in milliseconds since epoch (UTC)
 * @param mode "alert" or "booking"
 * @param preferredDays Specific days for this run (Locked Configuration)
 * @param preferredDates Specific dates for this run (Locked Configuration)
 * @param timezone IANA timezone ID (e.g., "America/Los_Angeles") - section 3.7, 5.3.5
 * @param isRecurring Whether this schedule repeats daily
 * @param remainingOccurrences Stop after this many runs (0 = infinite)
 * @param endDateMillis Stop after this absolute date
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
 * Single source of truth for all app configuration, stored in DataStore.
 * @param general General settings
 * @param admin Admin/site-specific configuration
 * @param scheduledRuns List of scheduled runs
 */
@Serializable
data class AppConfig(
    val general: GeneralSettings = GeneralSettings(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: List<ScheduledRun> = emptyList()
)

/**
 * LogEntry data class (section 7)
 * Represents a single log entry.
 * @param timestamp Timestamp in milliseconds since epoch
 * @param level Log level (INFO, WARN, ERROR, etc.)
 * @param message Log message
 */
@Serializable
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)
