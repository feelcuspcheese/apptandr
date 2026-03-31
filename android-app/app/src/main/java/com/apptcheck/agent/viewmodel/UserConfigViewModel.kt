package com.apptcheck.agent.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.model.UserConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for User Config Screen.
 * Integrates with ConfigManager for persistent state across navigation.
 * Following TECHNICAL_SPEC.md section 3 - Centralised Configuration Manager.
 */
class UserConfigViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configManager = ConfigManager(application)
    
    private val _userConfig = MutableStateFlow(UserConfig())
    val userConfig: StateFlow<UserConfig> = _userConfig.asStateFlow()
    
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()
    
    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()
    
    init {
        loadConfig()
    }
    
    /**
     * Load user config from ConfigManager (DataStore)
     */
    fun loadConfig() {
        viewModelScope.launch {
            try {
                val config = configManager.loadConfig()
                _userConfig.value = config.user
            } catch (e: Exception) {
                _userConfig.value = UserConfig()
            }
        }
    }
    
    /**
     * Save user config to ConfigManager (DataStore)
     */
    fun saveConfig(
        mode: String,
        strikeTime: String,
        preferredDays: List<String>,
        ntfyTopic: String,
        preferredSlug: String,
        checkWindow: String,
        checkInterval: String,
        requestJitter: String,
        monthsToCheck: Int,
        preWarmOffset: String,
        maxWorkers: Int,
        restCycleChecks: Int,
        restCycleDuration: String
    ) {
        viewModelScope.launch {
            try {
                val userConfig = UserConfig(
                    mode = mode,
                    strikeTime = strikeTime,
                    preferredDays = preferredDays,
                    ntfyTopic = ntfyTopic,
                    preferredSlug = preferredSlug,
                    checkWindow = checkWindow,
                    checkInterval = checkInterval,
                    requestJitter = requestJitter,
                    monthsToCheck = monthsToCheck,
                    preWarmOffset = preWarmOffset,
                    maxWorkers = maxWorkers,
                    restCycleChecks = restCycleChecks,
                    restCycleDuration = restCycleDuration
                )
                configManager.updateUserConfig(userConfig)
                _userConfig.value = userConfig
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
