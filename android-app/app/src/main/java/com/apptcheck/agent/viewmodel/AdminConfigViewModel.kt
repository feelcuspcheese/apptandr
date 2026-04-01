package com.apptcheck.agent.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.model.AdminConfig
import com.apptcheck.agent.model.SiteConfig
import com.apptcheck.agent.model.Museum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Admin Config Screen.
 * Integrates with ConfigManager for persistent state across navigation.
 * Following TECHNICAL_SPEC.md section 3 - Centralised Configuration Manager.
 */
class AdminConfigViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configManager = ConfigManager(application)
    
    private val _adminConfig = MutableStateFlow(AdminConfig())
    val adminConfig: StateFlow<AdminConfig> = _adminConfig.asStateFlow()
    
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()
    
    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()
    
    init {
        // Collect config flow reactively - ensures updates from other screens are received
        viewModelScope.launch {
            configManager.configFlow.collect { config ->
                _adminConfig.value = config.admin
            }
        }
    }
    
    /**
     * Load admin config from ConfigManager (DataStore)
     * Note: No longer needed as config is loaded reactively via Flow in init block.
     * Kept for backward compatibility but does nothing.
     */
    fun loadConfig() {
        // Config is now loaded reactively via Flow collection in init block
    }
    
    /**
     * Save admin config to ConfigManager (DataStore)
     */
    fun saveConfig(
        activeSite: String,
        sites: MutableMap<String, SiteConfig>
    ) {
        viewModelScope.launch {
            try {
                val adminConfig = AdminConfig(
                    activeSite = activeSite,
                    sites = sites
                )
                configManager.updateAdminConfig(adminConfig)
                _adminConfig.value = adminConfig
                _saveSuccess.value = true
                _saveError.value = false
                
                // Reset success flag after delay
                kotlinx.coroutines.delay(2000)
                _saveSuccess.value = false
            } catch (e: Exception) {
                _saveError.value = true
                _saveSuccess.value = false
                
                // Reset error flag after delay
                kotlinx.coroutines.delay(2000)
                _saveError.value = false
            }
        }
    }
    
    /**
     * Reset feedback flags
     */
    fun resetFeedback() {
        _saveSuccess.value = false
        _saveError.value = false
    }
}
