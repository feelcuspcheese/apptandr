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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

private val jsonDecoder = Json { ignoreUnknownKeys = true }
private val jsonEncoder = Json { encodeDefaults = true }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

class ConfigManager private constructor(private val context: Context) {
    
    companion object {
        private val CONFIG_KEY = stringPreferencesKey("app_config")
        private val WIZARD_COMPLETED_KEY = booleanPreferencesKey("wizard_completed")
        private val configLoadedOnce = AtomicBoolean(false)
        
        @Volatile
        private var instance: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    suspend fun isWizardCompleted(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[WIZARD_COMPLETED_KEY] ?: false
        }.first()
    }
    
    suspend fun setWizardCompleted(completed: Boolean) {
        context.dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[WIZARD_COMPLETED_KEY] = completed
            }.toPreferences()
        }
    }
    
    val configFlow: Flow<AppConfig> = context.dataStore.data
        .catch { e ->
            LogManager.addLog("ERROR", "DataStore corruption detected: ${e.message}")
            emit(emptyPreferences()) 
        }
        .map { prefs ->
            val json = prefs[CONFIG_KEY]
            if (json == null) {
                AppConfig()
            } else {
                try {
                    val config = jsonDecoder.decodeFromString<AppConfig>(json)
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
                    config.copy(scheduledRuns = validRuns)
                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to parse config: ${e.message}")
                    AppConfig() 
                }
            }.also { config ->
                if (configLoadedOnce.compareAndSet(false, true)) {
                    LogManager.addLog("INFO", "Configuration loaded: activeSite=${config.admin.activeSite}, runs=${config.scheduledRuns.size}")
                }
            }
        }
    
    suspend fun updateGeneral(general: GeneralSettings) {
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updated = current.copy(general = general)
            prefs.withConfig(updated)
        }
        LogManager.addLog("INFO", "General configuration saved: mode=${general.mode}, strikeTime=${general.strikeTime}")
    }
    
    suspend fun updateAdmin(admin: AdminConfig) {
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val activeSite = admin.activeSite
            val validMuseums = admin.sites[activeSite]?.museums?.keys ?: emptySet()
            val newPreferredSlug = if (current.general.preferredMuseumSlug in validMuseums) {
                current.general.preferredMuseumSlug
            } else {
                ""
            }
            val updatedGeneral = current.general.copy(preferredMuseumSlug = newPreferredSlug)
            val updated = current.copy(admin = admin, general = updatedGeneral)
            prefs.withConfig(updated)
        }
        LogManager.addLog("INFO", "Admin configuration saved: activeSite=${admin.activeSite}")
    }
    
    suspend fun addScheduledRun(run: ScheduledRun) {
        if (run.siteKey.isBlank()) throw IllegalArgumentException("Site key cannot be blank")
        if (run.museumSlug.isBlank()) throw IllegalArgumentException("Museum slug cannot be blank")
        if (run.dropTimeMillis <= System.currentTimeMillis()) throw IllegalArgumentException("Drop time must be in the future")
        if (run.mode !in listOf("alert", "booking")) throw IllegalArgumentException("Mode must be 'alert' or 'booking'")
        if (run.timezone.isBlank()) throw IllegalArgumentException("Timezone cannot be blank")

        val config = configFlow.first()
        val site = config.admin.sites[run.siteKey] ?: throw IllegalArgumentException("Site not found: ${run.siteKey}")
        if (run.museumSlug !in site.museums.keys) throw IllegalArgumentException("Museum not found: ${run.museumSlug}")
        if (run.credentialId != null && run.credentialId !in site.credentials.map { it.id }) {
            throw IllegalArgumentException("Credential not found: ${run.credentialId}")
        }

        LogManager.addLog("INFO", "Scheduled run added: id=${run.id}, site=${run.siteKey}, museum=${run.museumSlug}, dropTime=${run.dropTimeMillis}, mode=${run.mode}, timezone=${run.timezone}")
        
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = current.scheduledRuns + run
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }
    
    suspend fun removeScheduledRun(runId: String) {
        LogManager.addLog("INFO", "Scheduled run removed (user delete): id=$runId")
        
        context.dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = current.scheduledRuns.filter { it.id != runId }
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }
    
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
                } ?: true
                
                if (museumExists && credentialExists) {
                    validRunIds.add(run.id)
                } else {
                    removedRunIds.add(run.id)
                    LogManager.addLog("WARN", "Removed invalid scheduled run ${run.id}: museum=${!museumExists}, credential=${!credentialExists}")
                }
            }
            
            val updatedRuns = current.scheduledRuns.filter { it.id in validRunIds }
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
        return removedRunIds
    }
    
    fun buildAgentConfig(run: ScheduledRun, config: AppConfig): String? {
        val site = config.admin.sites[run.siteKey] ?: return null
        val museum = site.museums[run.museumSlug] ?: return null

        val credential = run.credentialId?.let { id ->
            site.credentials.find { it.id == id }
        } ?: site.defaultCredentialId?.let { id ->
            site.credentials.find { it.id == id }
        }
        val username = credential?.username ?: ""
        val password = credential?.password ?: ""
        val email    = credential?.email    ?: ""

        val requestJson = buildJsonObject {
            put("siteKey",    run.siteKey)
            put("museumSlug", run.museumSlug)
            put("dropTime",   java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString())
            put("mode",       run.mode)
            put("timezone",   run.timezone)
            put("fullConfig", buildJsonObject {
                put("active_site",         config.admin.activeSite)
                put("mode",                config.general.mode)
                put("strike_time",         config.general.strikeTime)
                put("preferred_days",      buildJsonArray {
                    config.general.preferredDays.forEach { add(it) }
                })
                put("ntfy_topic",          config.general.ntfyTopic)
                put("check_window",        config.general.checkWindow)
                put("check_interval",      config.general.checkInterval)
                put("request_jitter",      config.general.requestJitter)
                put("months_to_check",     config.general.monthsToCheck)
                put("pre_warm_offset",     config.general.preWarmOffset)
                put("max_workers",         config.general.maxWorkers)
                put("rest_cycle_checks",   config.general.restCycleChecks)
                put("rest_cycle_duration", config.general.restCycleDuration)
                put("sites", buildJsonObject {
                    put(run.siteKey, buildJsonObject {
                        put("name",                 site.name)
                        put("baseurl",              site.baseUrl)
                        put("availabilityendpoint", site.availabilityEndpoint)
                        put("digital",              site.digital)
                        put("physical",             site.physical)
                        put("location",             site.location)
                        put("bookinglinkselector",  Defaults.BOOKING_LINK_SELECTOR)
                        put("loginform", buildJsonObject {
                            put("usernamefield",    Defaults.USERNAME_FIELD)
                            put("passwordfield",    Defaults.PASSWORD_FIELD)
                            put("submitbutton",     Defaults.SUBMIT_BUTTON)
                            put("csrfselector",     "")
                            put("username",         username)
                            put("password",         password)
                            put("email",            email)
                            put("authidselector",   Defaults.AUTH_ID_SELECTOR)
                            put("loginurlselector", Defaults.LOGIN_URL_SELECTOR)
                        })
                        put("bookingform", buildJsonObject {
                            put("actionurl",  "")
                            put("fields",     buildJsonArray { })
                            put("emailfield", Defaults.EMAIL_FIELD)
                        })
                        put("successindicator", Defaults.SUCCESS_INDICATOR)
                        put("museums", buildJsonObject {
                            put(museum.slug, buildJsonObject {
                                put("name",     museum.name)
                                put("slug",     museum.slug)
                                put("museumid", museum.museumId)
                            })
                        })
                        put("preferredslug", config.general.preferredMuseumSlug)
                    })
                })
            })
        }

        return requestJson.toString()
    }
}

private val CONFIG_KEY = stringPreferencesKey("app_config")

fun Preferences.toAppConfig(): AppConfig {
    val json = this[CONFIG_KEY] ?: return AppConfig()
    return jsonDecoder.decodeFromString(json)
}

fun Preferences.withConfig(config: AppConfig): Preferences {
    val json = jsonEncoder.encodeToString(config)
    return toMutablePreferences().apply { this[CONFIG_KEY] = json }.toPreferences()
}
