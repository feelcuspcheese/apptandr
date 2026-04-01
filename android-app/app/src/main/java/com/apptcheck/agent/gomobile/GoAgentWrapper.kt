package com.apptcheck.agent.gomobile

import mobile.MobileAgent

/**
 * Wrapper for the Go MobileAgent AAR.
 * Provides interface to start/stop the Go booking agent and receive logs/status.
 * 
 * Following TECHNICAL_SPEC.md section 5:
 * - setLogCallback: Receives log messages from Go agent
 * - setStatusCallback: Receives status updates from Go agent  
 * - start: Starts the agent with JSON config
 * - stop: Stops the running agent
 * - isRunning: Checks if agent is currently running
 */
object GoAgentWrapper {
    
    private var mobileAgent: MobileAgent? = null
    private var isInitialized = false
    
    /**
     * Initialize the MobileAgent instance.
     * Must be called before any other operations.
     */
    fun initialize() {
        if (!isInitialized) {
            mobileAgent = MobileAgent()
            isInitialized = true
        }
    }
    
    /**
     * Set callback to receive log messages from Go agent.
     * @param callback Function that receives log strings
     */
    fun setLogCallback(callback: (String) -> Unit) {
        ensureInitialized()
        mobileAgent?.setLogCallback { message ->
            callback(message)
        }
    }
    
    /**
     * Set callback to receive status updates from Go agent.
     * @param callback Function that receives status strings ("running", "stopped", etc.)
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        ensureInitialized()
        mobileAgent?.setStatusCallback { status ->
            callback(status)
        }
    }
    
    /**
     * Start the Go booking agent with the given JSON configuration.
     * @param configJson JSON configuration string built by ConfigManager.buildAgentConfig()
     * @return true if started successfully, false if already running or invalid config
     */
    fun start(configJson: String): Boolean {
        ensureInitialized()
        return mobileAgent?.start(configJson) ?: false
    }
    
    /**
     * Stop the currently running Go booking agent.
     */
    fun stop() {
        ensureInitialized()
        mobileAgent?.stop()
    }
    
    /**
     * Check if the Go booking agent is currently running.
     * @return true if running, false otherwise
     */
    fun isRunning(): Boolean {
        ensureInitialized()
        return mobileAgent?.isRunning() ?: false
    }
    
    /**
     * Ensure the MobileAgent is initialized before use.
     */
    private fun ensureInitialized() {
        if (!isInitialized) {
            initialize()
        }
    }
}
