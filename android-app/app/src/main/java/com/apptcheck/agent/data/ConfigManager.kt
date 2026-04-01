package com.apptcheck.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apptcheck.agent.model.AdminConfig
import com.apptcheck.agent.model.AppConfig
import com.apptcheck.agent.model.Defaults
import com.apptcheck.agent.model.Museum
import com.apptcheck.agent.model.ScheduledRun
import com.apptcheck.agent.model.SiteConfig
import com.apptcheck.agent.model.UserConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * Centralised Configuration Manager following TECHNICAL_SPEC.md section 3.
 * Single source of truth using DataStore (Preferences).
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

class ConfigManager(private val context: Context) {
    
    private val CONFIG_KEY = stringPreferencesKey("app_config_json")
    
    /**
     * Load config from DataStore, fallback to defaults
     */
    suspend fun loadConfig(): AppConfig {
        return context.dataStore.data.map { preferences ->
            val json = preferences[CONFIG_KEY]
            if (json != null) {
                try {
                    parseAppConfig(json)
                } catch (e: Exception) {
                    AppConfig() // Return defaults on parse error
                }
            } else {
                AppConfig()
            }
        }.first()
    }
    
    /**
     * Save complete app config
     */
    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { preferences ->
            preferences[CONFIG_KEY] = serializeAppConfig(config)
        }
    }
    
    /**
     * Update user config only
     */
    suspend fun updateUserConfig(user: UserConfig) {
        val config = loadConfig()
        val updated = config.copy(user = user)
        saveConfig(updated)
    }
    
    /**
     * Update admin config only
     */
    suspend fun updateAdminConfig(admin: AdminConfig) {
        val config = loadConfig()
        val updated = config.copy(admin = admin)
        saveConfig(updated)
    }
    
    /**
     * Add a scheduled run
     */
    suspend fun addScheduledRun(run: ScheduledRun) {
        val config = loadConfig()
        config.scheduledRuns.add(run)
        saveConfig(config)
    }
    
    /**
     * Remove a scheduled run by ID
     */
    suspend fun removeScheduledRun(runId: String) {
        val config = loadConfig()
        config.scheduledRuns.removeAll { it.id == runId }
        saveConfig(config)
    }
    
    /**
     * Get all scheduled runs
     */
    suspend fun getScheduledRuns(): List<ScheduledRun> {
        return loadConfig().scheduledRuns
    }
    
    /**
     * Build JSON config for Go agent - merges user config, admin config, and protected defaults.
     * Following TECHNICAL_SPEC.md section 3, buildAgentConfig function.
     */
    fun buildAgentConfig(run: ScheduledRun): String {
        val config = runBlocking { loadConfig() }
        val site = config.admin.sites[run.siteKey] ?: error("Site not found: ${run.siteKey}")
        val museum = site.museums[run.museumSlug] ?: error("Museum not found: ${run.museumSlug}")
        
        // Helper to create JSON array from list
        fun jsonArray(list: List<String>): String {
            return list.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }
        }
        
        // Assemble the full config structure expected by the Go agent.
        // This is identical to the JSON used in the web dashboard (fullConfig).
        return """{
          "siteKey": "${run.siteKey}",
          "museumSlug": "${run.museumSlug}",
          "dropTime": "${Instant.ofEpochMilli(run.dropTimeMillis)}",
          "mode": "${run.mode}",
          "timezone": "UTC",
          "fullConfig": {
            "active_site": "${config.admin.activeSite}",
            "mode": "${config.user.mode}",
            "strike_time": "${config.user.strikeTime}",
            "preferred_days": ${jsonArray(config.user.preferredDays)},
            "ntfy_topic": "${config.user.ntfyTopic}",
            "check_window": "${config.user.checkWindow}",
            "check_interval": "${config.user.checkInterval}",
            "request_jitter": "${config.user.requestJitter}",
            "months_to_check": ${config.user.monthsToCheck},
            "pre_warm_offset": "${config.user.preWarmOffset}",
            "max_workers": ${config.user.maxWorkers},
            "rest_cycle_checks": ${config.user.restCycleChecks},
            "rest_cycle_duration": "${config.user.restCycleDuration}",
            "sites": {
              "${run.siteKey}": {
                "name": "${site.name}",
                "baseurl": "${site.baseUrl}",
                "availabilityendpoint": "${site.availabilityEndpoint}",
                "digital": ${site.digital},
                "physical": ${site.physical},
                "location": "${site.location}",
                "bookinglinkselector": "${Defaults.BOOKING_LINK_SELECTOR}",
                "loginform": {
                  "usernamefield": "${Defaults.USERNAME_FIELD}",
                  "passwordfield": "${Defaults.PASSWORD_FIELD}",
                  "submitbutton": "${Defaults.SUBMIT_BUTTON}",
                  "csrfselector": "",
                  "username": "${site.loginUsername}",
                  "password": "${site.loginPassword}",
                  "email": "${site.loginEmail}",
                  "authidselector": "${Defaults.AUTH_ID_SELECTOR}",
                  "loginurlselector": "${Defaults.LOGIN_URL_SELECTOR}"
                },
                "bookingform": {
                  "actionurl": "",
                  "fields": [],
                  "emailfield": "${Defaults.EMAIL_FIELD}"
                },
                "successindicator": "Thank you!",
                "museums": {
                  "${museum.slug}": {
                    "name": "${museum.name}",
                    "slug": "${museum.slug}",
                    "museumid": "${museum.museumId}"
                  }
                },
                "preferredslug": "${config.user.preferredSlug}"
              }
            }
          }
        }""".trimIndent()
    }
    
    // Simple serialization/deserialization (in production, use kotlinx.serialization or Gson)
    private fun serializeAppConfig(config: AppConfig): String {
        // For MVP, we'll use a simple approach - in production use proper JSON library
        val userJson = serializeUserConfig(config.user)
        val adminJson = serializeAdminConfig(config.admin)
        val runsJson = config.scheduledRuns.joinToString(prefix = "[", postfix = "]") { run ->
            """{"id":"${run.id}","siteKey":"${run.siteKey}","museumSlug":"${run.museumSlug}","dropTimeMillis":${run.dropTimeMillis},"mode":"${run.mode}"}"""
        }
        return """{"user":$userJson,"admin":$adminJson,"scheduledRuns":$runsJson}"""
    }
    
    private fun parseAppConfig(json: String): AppConfig {
        // Simple parsing - in production use proper JSON library
        try {
            val user = extractJsonObject(json, "user")?.let { parseUserConfig(it) } ?: UserConfig()
            val admin = extractJsonObject(json, "admin")?.let { parseAdminConfig(it) } ?: AdminConfig()
            val runsJson = extractArray(json, "scheduledRuns")
            val runs = parseScheduledRuns(runsJson)
            return AppConfig(user, admin, runs.toMutableList())
        } catch (e: Exception) {
            return AppConfig()
        }
    }
    
    private fun serializeUserConfig(user: UserConfig): String {
        val daysJson = user.preferredDays.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """{"mode":"${user.mode}","strikeTime":"${user.strikeTime}","preferredDays":$daysJson,"ntfyTopic":"${user.ntfyTopic}","preferredSlug":"${user.preferredSlug}","checkWindow":"${user.checkWindow}","checkInterval":"${user.checkInterval}","requestJitter":"${user.requestJitter}","monthsToCheck":${user.monthsToCheck},"preWarmOffset":"${user.preWarmOffset}","maxWorkers":${user.maxWorkers},"restCycleChecks":${user.restCycleChecks},"restCycleDuration":"${user.restCycleDuration}"}"""
    }
    
    private fun parseUserConfig(json: String): UserConfig {
        return UserConfig(
            mode = extractString(json, "mode") ?: "alert",
            strikeTime = extractString(json, "strikeTime") ?: "09:00",
            preferredDays = extractStringArray(json, "preferredDays") ?: listOf("Monday", "Wednesday", "Friday"),
            ntfyTopic = extractString(json, "ntfyTopic") ?: "myappointments",
            preferredSlug = extractString(json, "preferredSlug") ?: "",
            checkWindow = extractString(json, "checkWindow") ?: Defaults.CHECK_WINDOW,
            checkInterval = extractString(json, "checkInterval") ?: Defaults.CHECK_INTERVAL,
            requestJitter = extractString(json, "requestJitter") ?: Defaults.REQUEST_JITTER,
            monthsToCheck = extractInt(json, "monthsToCheck") ?: Defaults.MONTHS_TO_CHECK,
            preWarmOffset = extractString(json, "preWarmOffset") ?: Defaults.PRE_WARM_OFFSET,
            maxWorkers = extractInt(json, "maxWorkers") ?: Defaults.MAX_WORKERS,
            restCycleChecks = extractInt(json, "restCycleChecks") ?: Defaults.REST_CYCLE_CHECKS,
            restCycleDuration = extractString(json, "restCycleDuration") ?: Defaults.REST_CYCLE_DURATION
        )
    }
    
    private fun serializeAdminConfig(admin: AdminConfig): String {
        val sitesJson = admin.sites.entries.joinToString(prefix = "{", postfix = "}") { (key, site) ->
            "\"$key\":${serializeSiteConfig(site)}"
        }
        return """{"activeSite":"${admin.activeSite}","sites":$sitesJson}"""
    }
    
    private fun serializeSiteConfig(site: SiteConfig): String {
        val museumsJson = site.museums.entries.joinToString(prefix = "{", postfix = "}") { (key, museum) ->
            "\"$key\":{\"name\":\"${museum.name}\",\"slug\":\"${museum.slug}\",\"museumId\":\"${museum.museumId}\"}"
        }
        return """{"name":"${site.name}","baseUrl":"${site.baseUrl}","availabilityEndpoint":"${site.availabilityEndpoint}","digital":${site.digital},"physical":${site.physical},"location":"${site.location}","museums":$museumsJson,"loginUsername":"${site.loginUsername}","loginPassword":"${site.loginPassword}","loginEmail":"${site.loginEmail}"}"""
    }
    
    private fun parseAdminConfig(json: String): AdminConfig {
        val activeSite = extractString(json, "activeSite") ?: "spl"
        val sites = mutableMapOf<String, SiteConfig>()
        
        // Parse SPL site
        extractJsonObject(json, "sites")?.let { sitesJson ->
            extractJsonObject(sitesJson, "spl")?.let { 
                sites["spl"] = parseSiteConfig(it)
            }
            extractJsonObject(sitesJson, "kcls")?.let {
                sites["kcls"] = parseSiteConfig(it)
            }
        }
        
        // Ensure we have at least default sites
        if (sites.isEmpty()) {
            return AdminConfig()
        }
        
        return AdminConfig(activeSite = activeSite, sites = sites)
    }
    
    private fun parseSiteConfig(json: String): SiteConfig {
        val museums = mutableMapOf<String, Museum>()
        
        // Parse museums more robustly - handle both empty and populated museums
        extractJsonObject(json, "museums")?.let { museumsJson ->
            if (museumsJson.isNotBlank() && museumsJson != "{}") {
                // Try to parse each museum entry
                // Format: "slug":{"name":"...", "slug":"...", "museumId":"..."}
                val museumPattern = "\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
                museumPattern.findAll(museumsJson).forEach { match ->
                    try {
                        val slug = match.groupValues[1]
                        val museumData = match.groupValues[2]
                        val name = extractString("{$museumData}", "name") ?: ""
                        val museumId = extractString("{$museumData}", "museumId") ?: ""
                        if (slug.isNotEmpty()) {
                            museums[slug] = Museum(name, slug, museumId)
                        }
                    } catch (e: Exception) {
                        // Skip invalid museum entries
                    }
                }
            }
        }
        
        return SiteConfig(
            name = extractString(json, "name") ?: "",
            baseUrl = extractString(json, "baseUrl") ?: "",
            availabilityEndpoint = extractString(json, "availabilityEndpoint") ?: "",
            digital = extractBoolean(json, "digital") ?: true,
            physical = extractBoolean(json, "physical") ?: false,
            location = extractString(json, "location") ?: "0",
            museums = museums,
            loginUsername = extractString(json, "loginUsername") ?: "",
            loginPassword = extractString(json, "loginPassword") ?: "",
            loginEmail = extractString(json, "loginEmail") ?: ""
        )
    }
    
    private fun parseScheduledRuns(json: String?): MutableList<ScheduledRun> {
        if (json.isNullOrBlank() || json == "[]") return mutableListOf()
        
        val runs = mutableListOf<ScheduledRun>()
        // Simple parsing - split by },{ 
        val runStrings = json.trim('[', ']').split("},{").map { it.trim('{', '}') }
        runStrings.forEach { runStr ->
            try {
                runs.add(
                    ScheduledRun(
                        id = extractString(runStr, "id") ?: UUID.randomUUID().toString(),
                        siteKey = extractString(runStr, "siteKey") ?: "",
                        museumSlug = extractString(runStr, "museumSlug") ?: "",
                        dropTimeMillis = extractLong(runStr, "dropTimeMillis") ?: 0L,
                        mode = extractString(runStr, "mode") ?: "alert"
                    )
                )
            } catch (e: Exception) {
                // Skip invalid runs
            }
        }
        return runs
    }
    
    // Helper extraction functions for simple JSON parsing
    private fun extractJsonObject(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\})".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }
    
    private fun extractArray(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(\\[[^\\]]*\\])".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }
    
    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }
    
    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    
    private fun extractLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
    
    private fun extractBoolean(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }
    
    private fun extractStringArray(json: String, key: String): List<String>? {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
    }
}
