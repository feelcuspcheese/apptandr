package com.apptcheck.agent.model

import java.util.UUID

/**
 * Data models following TECHNICAL_SPEC.md section 2.2 - 2.5
 */

// 2.2 User Config (editable by user)
data class UserConfig(
    var mode: String = "alert",            // "alert" or "booking"
    var strikeTime: String = "09:00",      // HH:MM
    var preferredDays: List<String> = listOf("Monday", "Wednesday", "Friday"),
    var ntfyTopic: String = "myappointments",
    var preferredSlug: String = "",        // museum slug (string)
    // Performance tuning (advanced)
    var checkWindow: String = Defaults.CHECK_WINDOW,
    var checkInterval: String = Defaults.CHECK_INTERVAL,
    var requestJitter: String = Defaults.REQUEST_JITTER,
    var monthsToCheck: Int = Defaults.MONTHS_TO_CHECK,
    var preWarmOffset: String = Defaults.PRE_WARM_OFFSET,
    var maxWorkers: Int = Defaults.MAX_WORKERS,
    var restCycleChecks: Int = Defaults.REST_CYCLE_CHECKS,
    var restCycleDuration: String = Defaults.REST_CYCLE_DURATION
)

// 2.3 Admin Config (site-specific, user credentials)
data class AdminConfig(
    var activeSite: String = "spl",      // "spl" or "kcls"
    val sites: MutableMap<String, SiteConfig> = mutableMapOf(
        "spl" to SiteConfig(
            name = "SPL",
            baseUrl = "https://spl.libcal.com",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0",
            museums = mutableMapOf(
                "seattle-art-museum" to Museum("Seattle Art Museum", "seattle-art-museum", "7f2ac5c414b2"),
                "zoo" to Museum("Woodland Park Zoo", "zoo", "033bbf08993f")
            ),
            loginUsername = "",
            loginPassword = "",
            loginEmail = ""
        ),
        "kcls" to SiteConfig(
            name = "KCLS",
            baseUrl = "https://rooms.kcls.org",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0",
            museums = mutableMapOf(
                "kidsquest" to Museum("KidsQuest Children's Museum", "kidsquest", "9ec25160a8a0")
            ),
            loginUsername = "",
            loginPassword = "",
            loginEmail = ""
        )
    )
)

data class SiteConfig(
    val name: String,
    var baseUrl: String,
    var availabilityEndpoint: String,
    var digital: Boolean,
    var physical: Boolean,
    var location: String,
    val museums: MutableMap<String, Museum>,   // slug -> Museum
    var loginUsername: String,
    var loginPassword: String,
    var loginEmail: String
)

data class Museum(
    val name: String,
    val slug: String,
    val museumId: String
)

// 2.4 Scheduled Run
data class ScheduledRun(
    val id: String = UUID.randomUUID().toString(),
    val siteKey: String,
    val museumSlug: String,
    val dropTimeMillis: Long,       // absolute time in milliseconds
    val mode: String                // "alert" or "booking"
)

// 2.5 Complete App Config (stored in DataStore)
data class AppConfig(
    val user: UserConfig = UserConfig(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: MutableList<ScheduledRun> = mutableListOf()
)

// Log entry for LogManager
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)

// Schedule result for UI feedback
data class ScheduleResult(
    val success: Boolean,
    val error: String? = null
)
