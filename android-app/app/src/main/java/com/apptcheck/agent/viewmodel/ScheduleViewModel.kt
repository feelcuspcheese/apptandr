package com.apptcheck.agent.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.model.ScheduledRun
import com.apptcheck.agent.model.ScheduleResult
import com.apptcheck.agent.scheduler.AlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ViewModel for Schedule Screen.
 * Manages site/museum selection based on admin config and handles scheduling.
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configManager = ConfigManager(application)
    private val alarmScheduler = AlarmScheduler(application)
    
    data class UiState(
        val selectedSite: String = "spl",
        val selectedMuseum: String = "",
        val selectedMode: String = "alert",
        val selectedDateTime: String = "",
        val availableSites: List<String> = listOf("spl", "kcls"),
        val availableMuseums: List<String> = emptyList(),
        val scheduledRuns: List<ScheduledRun> = emptyList()
    )
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val _saveResult = MutableStateFlow<ScheduleResult?>(null)
    val saveResult: StateFlow<ScheduleResult?> = _saveResult.asStateFlow()
    
    init {
        loadConfig()
    }
    
    /**
     * Load config from ConfigManager and update UI state
     */
    fun loadConfig() {
        viewModelScope.launch {
            try {
                val config = configManager.loadConfig()
                val currentSite = _uiState.value.selectedSite
                
                // Get available sites from admin config - dynamically load from saved config
                val availableSites = config.admin.sites.keys.toList()
                
                // Ensure current site is valid, otherwise use first available site
                val validCurrentSite = if (currentSite in availableSites) currentSite else availableSites.firstOrNull() ?: "spl"
                
                // Get museums for currently selected site from the loaded config
                val museums = config.admin.sites[validCurrentSite]?.museums?.keys?.toList() ?: emptyList()
                
                // Get scheduled runs
                val scheduledRuns = config.scheduledRuns.toList()
                
                _uiState.value = _uiState.value.copy(
                    selectedSite = validCurrentSite,
                    availableSites = availableSites,
                    availableMuseums = museums,
                    scheduledRuns = scheduledRuns
                )
            } catch (e: Exception) {
                // On error, keep defaults
            }
        }
    }
    
    /**
     * Called when user selects a site
     */
    fun onSiteSelected(site: String) {
        viewModelScope.launch {
            try {
                val config = configManager.loadConfig()
                val museums = config.admin.sites[site]?.museums?.keys?.toList() ?: emptyList()
                
                _uiState.value = _uiState.value.copy(
                    selectedSite = site,
                    selectedMuseum = "", // Reset museum when site changes
                    availableMuseums = museums
                )
            } catch (e: Exception) {
                // Keep current state on error
            }
        }
    }
    
    /**
     * Called when user selects a museum
     */
    fun onMuseumSelected(museum: String) {
        viewModelScope.launch {
            try {
                val config = configManager.loadConfig()
                val currentSite = _uiState.value.selectedSite
                // Validate that the museum exists for the selected site by checking the saved config
                val museumsForSite = config.admin.sites[currentSite]?.museums?.keys?.toList() ?: emptyList()
                
                // Only accept the museum if it exists in the saved config for this site
                if (museum in museumsForSite) {
                    _uiState.value = _uiState.value.copy(selectedMuseum = museum)
                } else {
                    // Museum doesn't exist for this site - reload museums from config
                    _uiState.value = _uiState.value.copy(
                        selectedMuseum = "",
                        availableMuseums = museumsForSite
                    )
                }
            } catch (e: Exception) {
                // Keep current state on error
            }
        }
    }
    
    /**
     * Called when user selects a mode
     */
    fun onModeSelected(mode: String) {
        _uiState.value = _uiState.value.copy(selectedMode = mode)
    }
    
    /**
     * Called when user selects date/time
     */
    fun onDateTimeSelected(dateTime: String) {
        _uiState.value = _uiState.value.copy(selectedDateTime = dateTime)
    }
    
    /**
     * Schedule a new run
     */
    fun scheduleRun(site: String, museum: String, mode: String, dateTimeStr: String) {
        viewModelScope.launch {
            try {
                // Parse the date time string
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val localDateTime = LocalDateTime.parse(dateTimeStr, formatter)
                val zoneId = ZoneId.systemDefault()
                val millis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()
                
                // Validate that the time is in the future
                val now = System.currentTimeMillis()
                if (millis <= now) {
                    _saveResult.value = ScheduleResult(
                        success = false,
                        error = "Selected time must be in the future"
                    )
                    return@launch
                }
                
                // Create ScheduledRun
                val run = ScheduledRun(
                    id = java.util.UUID.randomUUID().toString(),
                    siteKey = site,
                    museumSlug = museum,
                    dropTimeMillis = millis,
                    mode = mode
                )
                
                // Save to config
                configManager.addScheduledRun(run)
                
                // Schedule the alarm
                alarmScheduler.scheduleRun(run)
                
                // Update UI state with new run
                val updatedRuns = _uiState.value.scheduledRuns + run
                _uiState.value = _uiState.value.copy(scheduledRuns = updatedRuns)
                
                // Show success feedback
                _saveResult.value = ScheduleResult(success = true)
                
                // Clear result after delay
                kotlinx.coroutines.delay(3000)
                _saveResult.value = null
                
            } catch (e: Exception) {
                _saveResult.value = ScheduleResult(
                    success = false,
                    error = e.message ?: "Failed to schedule run"
                )
                
                // Clear error after delay
                kotlinx.coroutines.delay(3000)
                _saveResult.value = null
            }
        }
    }
    
    /**
     * Delete a scheduled run
     */
    fun deleteScheduledRun(runId: String) {
        viewModelScope.launch {
            try {
                // Cancel the alarm
                alarmScheduler.cancelRun(runId)
                
                // Remove from config
                configManager.removeScheduledRun(runId)
                
                // Update UI state
                val updatedRuns = _uiState.value.scheduledRuns.filter { it.id != runId }
                _uiState.value = _uiState.value.copy(scheduledRuns = updatedRuns)
            } catch (e: Exception) {
                // Handle error silently or show toast
            }
        }
    }
}
