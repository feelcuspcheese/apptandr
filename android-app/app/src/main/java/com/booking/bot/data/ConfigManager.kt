package com.booking.bot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Central Configuration Manager following TECHNICAL_SPEC.md section 4.
 * Single source of truth using DataStore with a single JSON key.
 * 
 * This manager provides reactive updates via configFlow - any change to configuration
 * automatically emits a new AppConfig, and all screens observing the flow will recompose.
 */

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

class ConfigManager private constructor(private val context: Context) {
    
    companion object {
        private val CONFIG_KEY = stringPreferencesKey("app_config")
        private val WIZARD_COMPLETED_KEY = booleanPreferencesKey("wizard_completed")
        
        @Volatile
        private var instance: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * Check if the first-run wizard has been completed.
     */
    suspend fun isWizardCompleted(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[WIZARD_COMPLETED_KEY] ?: false
        }.first()
    }
    
    /**
     * Set the wizard completed flag.
     */
    suspend fun setWizardCompleted(completed: Boolean) {
        context.dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[WIZARD_COMPLETED_KEY] = completed
            }.toPreferences()
        }
    }
    
    /**
     * Flow that emits AppConfig whenever DataStore changes.
     * Following section 4.2, this enables reactive updates across all screens.
     * Safe deserialization: filters out invalid ScheduledRun entries.
     */
    val configFlow: Flow<AppConfig> = context.dataStore.data
        .catch { e ->
            // Log corruption for CONF-05
            LogManager.addLog("ERROR", "DataStore corruption detected: ${e.message}")
            emit(emptyPreferences()) 
        }
        .map { prefs ->
            val json = prefs[CONFIG_KEY]
            if (json == null) {
                AppConfig()
            } else {
                try {
                    val config = Json.decodeFromString<AppConfig>(json)
                    // Filter out invalid scheduled runs (section 4.2)
                    val validRuns = config.scheduledRuns.filter { run ->
                        run.siteKey.isNotBlank() &&
                        run.museumSlug.isNotBlank() &&
                        run.dropTimeMillis > 0 &&
                        run.mode in listOf("alert", "booking") &&
                        run.timezone.isNotBlank()
                    }
                    if (validRuns.size != config.scheduledRuns.size) {
                        LogManager.addLog("WARN", "Removed ${config.scheduledRuns.size - validRuns.size} invalid scheduled runs")
                    }
                    config.copy(scheduledRuns = validRuns.toMutableList())
                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to parse config: ${e.message}")
                    AppConfig() // fallback to default
                }
            }.also { config ->
                // [7.4.1]: Log configuration loaded
                LogManager.addLog("INFO", "Configuration loaded: activeSite=${config.admin.activeSite}, runs=${config.scheduledRuns.size}")
            }
        }
    
    /**
     * Update general settings.
     * Following section 4.2, this updates only the general portion of AppConfig.
     */
    suspend fun updateGeneral(general: GeneralSettings) {
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updated = current.copy(general = general)
            prefs.withConfig(updated)
        }
        // [7.4.11]: Log general configuration saved with key details
        LogManager.addLog("INFO", "General configuration saved: mode=${general.mode}, strikeTime=${general.strikeTime}")
    }
    
    /**
     * Update admin configuration.
     * Following section 4.2, this also validates that preferredMuseumSlug is still valid
     * for the active site, clearing it if the museum no longer exists.
     */
    suspend fun updateAdmin(admin: AdminConfig) {
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            
            // Validate preferredMuseumSlug against active site's museums
            val activeSite = admin.activeSite
            val validMuseums = admin.sites[activeSite]?.museums?.keys ?: emptySet()
            val newPreferredSlug = if (current.general.preferredMuseumSlug in validMuseums) {
                current.general.preferredMuseumSlug
            } else {
                "" // Clear if invalid
            }
            
            val updatedGeneral = current.general.copy(preferredMuseumSlug = newPreferredSlug)
            val updated = current.copy(admin = admin, general = updatedGeneral)
            prefs.withConfig(updated)
        }
        // [7.4.11]: Log admin configuration saved with key details
        LogManager.addLog("INFO", "Admin configuration saved: activeSite=${admin.activeSite}")
    }
    
    /**
     * Add a scheduled run.
     * Following section 4.2, this validates all fields and throws IllegalArgumentException on failure.
     */
    suspend fun addScheduledRun(run: ScheduledRun) {
        // Validate all fields (section 4.2.1)
        if (run.siteKey.isBlank()) throw IllegalArgumentException("Site key cannot be blank")
        if (run.museumSlug.isBlank()) throw IllegalArgumentException("Museum slug cannot be blank")
        if (run.dropTimeMillis <= System.currentTimeMillis()) throw IllegalArgumentException("Drop time must be in the future")
        if (run.mode !in listOf("alert", "booking")) throw IllegalArgumentException("Mode must be 'alert' or 'booking'")
        if (run.timezone.isBlank()) throw IllegalArgumentException("Timezone cannot be blank")

        // Validate site/museum/credential existence
        val config = configFlow.first()
        val site = config.admin.sites[run.siteKey] ?: throw IllegalArgumentException("Site not found: ${run.siteKey}")
        if (run.museumSlug !in site.museums.keys) throw IllegalArgumentException("Museum not found: ${run.museumSlug}")
        if (run.credentialId != null && run.credentialId !in site.credentials.map { it.id }) {
            throw IllegalArgumentException("Credential not found: ${run.credentialId}")
        }

        // [7.4.2]: Log scheduled run added with full details per spec
        LogManager.addLog("INFO", "Scheduled run added: id=${run.id}, site=${run.siteKey}, museum=${run.museumSlug}, dropTime=${run.dropTimeMillis}, mode=${run.mode}, timezone=${run.timezone}")
        
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = (current.scheduledRuns + run).toMutableList()
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }
    
    /**
     * Remove a scheduled run by ID.
     * Following section 3.8, finished runs should be removed so they no longer appear.
     */
    suspend fun removeScheduledRun(runId: String) {
        // [7.4.3]: Log scheduled run removed (user delete)
        LogManager.addLog("INFO", "Scheduled run removed (user delete): id=$runId")
        
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = current.scheduledRuns.filter { it.id != runId }.toMutableList()
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }
    
    /**
     * Validate and clean up invalid scheduled runs.
     * Removes runs where museum or credential no longer exists (PROP-02, EDGE-09, EDGE-10).
     * Should be called periodically or when museums/credentials are deleted.
     */
    suspend fun cleanupInvalidRuns(): List<String> {
        val removedRunIds = mutableListOf<String>()
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val validRunIds = mutableSetOf<String>()
            
            current.scheduledRuns.forEach { run ->
                val site = current.admin.sites[run.siteKey]
                val museumExists = site?.museums?.containsKey(run.museumSlug) == true
                
                val credentialExists = run.credentialId?.let { id ->
                    site?.credentials?.any { it.id == id }
                } ?: site?.defaultCredentialId?.let { defaultId ->
                    site.credentials.any { it.id == defaultId }
                } ?: true // If no credential specified and no default, still valid
                
                if (museumExists && credentialExists) {
                    validRunIds.add(run.id)
                } else {
                    removedRunIds.add(run.id)
                    LogManager.addLog("WARN", "Removed invalid scheduled run ${run.id}: museum=${!museumExists}, credential=${!credentialExists}")
                }
            }
            
            val updatedRuns = current.scheduledRuns.filter { it.id in validRunIds }.toMutableList()
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
        return removedRunIds
    }
    
    /**
     * Build JSON config for Go agent.
     * Following section 4.3, this creates the exact JSON expected by mobile/agent.go.
     * Field names are case-sensitive and must match the Go struct exactly.
     * Returns null if required site/museum/credential is not found.
     */
    fun buildAgentConfig(run: ScheduledRun, config: AppConfig): String? {
        val site = config.admin.sites[run.siteKey] ?: return null
        val museum = site.museums[run.museumSlug] ?: return null
        
        // Determine credential to use
        val credential = run.credentialId?.let { id ->
            site.credentials.find { it.id == id }
        } ?: site.defaultCredentialId?.let { id ->
            site.credentials.find { it.id == id }
        }
        
        val (username, password, email) = credential?.let {
            Triple(it.username, it.password, it.email)
        } ?: Triple("", "", "")
        
        val fullConfig = mapOf(
            "active_site" to config.admin.activeSite,
            "mode" to config.general.mode,
            "strike_time" to config.general.strikeTime,
            "preferred_days" to config.general.preferredDays,
            "ntfy_topic" to config.general.ntfyTopic,
            "check_window" to config.general.checkWindow,
            "check_interval" to config.general.checkInterval,
            "request_jitter" to config.general.requestJitter,
            "months_to_check" to config.general.monthsToCheck,
            "pre_warm_offset" to config.general.preWarmOffset,
            "max_workers" to config.general.maxWorkers,
            "rest_cycle_checks" to config.general.restCycleChecks,
            "rest_cycle_duration" to config.general.restCycleDuration,
            "sites" to mapOf(
                run.siteKey to mapOf(
                    "name" to site.name,
                    "baseurl" to site.baseUrl,
                    "availabilityendpoint" to site.availabilityEndpoint,
                    "digital" to site.digital,
                    "physical" to site.physical,
                    "location" to site.location,
                    "bookinglinkselector" to Defaults.BOOKING_LINK_SELECTOR,
                    "loginform" to mapOf(
                        "usernamefield" to Defaults.USERNAME_FIELD,
                        "passwordfield" to Defaults.PASSWORD_FIELD,
                        "submitbutton" to Defaults.SUBMIT_BUTTON,
                        "csrfselector" to "",
                        "username" to username,
                        "password" to password,
                        "email" to email,
                        "authidselector" to Defaults.AUTH_ID_SELECTOR,
                        "loginurlselector" to Defaults.LOGIN_URL_SELECTOR
                    ),
                    "bookingform" to mapOf(
                        "actionurl" to "",
                        "fields" to emptyList<String>(),
                        "emailfield" to Defaults.EMAIL_FIELD
                    ),
                    "successindicator" to Defaults.SUCCESS_INDICATOR,
                    "museums" to mapOf(
                        museum.slug to mapOf(
                            "name" to museum.name,
                            "slug" to museum.slug,
                            "museumid" to museum.museumId
                        )
                    ),
                    "preferredslug" to config.general.preferredMuseumSlug
                )
            )
        )
        
        val request = mapOf(
            "siteKey" to run.siteKey,
            "museumSlug" to run.museumSlug,
            "dropTime" to java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString(),
            "mode" to run.mode,
            "timezone" to run.timezone,
            "fullConfig" to fullConfig
        )
        
        return Json { encodeDefaults = true }.encodeToString(request)
    }
}

// Extension functions for Preferences (section 4.2)

private val CONFIG_KEY = stringPreferencesKey("app_config")

fun Preferences.toAppConfig(): AppConfig {
    val json = this[CONFIG_KEY] ?: return AppConfig()
    return Json.decodeFromString(json)
}

fun Preferences.withConfig(config: AppConfig): Preferences {
    val json = Json.encodeToString(config)
    return toMutablePreferences().apply { this[CONFIG_KEY] = json }.toPreferences()
}
