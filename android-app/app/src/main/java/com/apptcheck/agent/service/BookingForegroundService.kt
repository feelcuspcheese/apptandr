package com.apptcheck.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apptcheck.agent.MainActivity
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.gomobile.GoAgentWrapper
import com.apptcheck.agent.model.ScheduledRun

/**
 * Foreground service that runs the Go booking agent.
 * Following TECHNICAL_SPEC.md section 8 and OVERVIEW.md architecture.
 * 
 * Integrates with the Go AAR (booking.aar) via GoAgentWrapper to:
 * - Start the Go agent with configuration from ConfigManager
 * - Forward Go agent logs to LogManager
 * - Update UI with real-time status
 */
class BookingForegroundService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "booking_agent_channel"
        private const val CHANNEL_NAME = "Booking Agent"
        
        // Static reference for UI to check if service is running
        var isRunning: Boolean = false
            private set
        
        // Action for stopping the service
        const val ACTION_STOP_AGENT = "com.apptcheck.agent.STOP_AGENT"
    }
    
    private var currentRun: ScheduledRun? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check for stop action
        if (intent?.action == ACTION_STOP_AGENT) {
            stopAgent()
            return START_NOT_STICKY
        }
        
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        val runId = intent.getStringExtra("run_id") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val siteKey = intent.getStringExtra("site_key") ?: ""
        val museumSlug = intent.getStringExtra("museum_slug") ?: ""
        val dropTimeMillis = intent.getLongExtra("drop_time", 0L)
        val mode = intent.getStringExtra("mode") ?: "alert"
        val startNow = intent.getBooleanExtra("start_now", false)
        
        currentRun = ScheduledRun(runId, siteKey, museumSlug, dropTimeMillis, mode)
        
        LogManager.addLog("INFO", "Starting booking service for run $runId${if (startNow) " (immediate)" else ""}")
        
        // Start as foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        isRunning = true
        
        // Launch the Go agent
        launchGoAgent()
        
        return START_NOT_STICKY
    }
    
    /**
     * Launch the Go agent with the configured parameters.
     * Integrates with MobileAgent AAR via GoAgentWrapper.
     */
    private fun launchGoAgent() {
        val run = currentRun ?: return
        
        try {
            val configManager = ConfigManager(this)
            val configJson = configManager.buildAgentConfig(run)
            
            LogManager.addLog("INFO", "Built agent config for site ${run.siteKey}, museum ${run.museumSlug}")
            
            // Initialize GoAgentWrapper and set up callbacks
            GoAgentWrapper.initialize()
            
            // Set up log callback to forward Go logs to LogManager
            GoAgentWrapper.setLogCallback { logMessage ->
                LogManager.addLog("GO_AGENT", logMessage)
                updateNotification(logMessage)
            }
            
            // Set up status callback to track running state
            GoAgentWrapper.setStatusCallback { status ->
                LogManager.addLog("INFO", "Go agent status: $status")
                if (status == "running") {
                    isRunning = true
                    updateNotification("Running booking agent...")
                } else if (status == "stopped" || status == "finished") {
                    updateNotification("Completed")
                    stopAgent()
                }
            }
            
            // Start the Go agent with the configuration
            val started = GoAgentWrapper.start(configJson)
            
            if (started) {
                LogManager.addLog("INFO", "[REAL] Go agent started in ${run.mode} mode")
                updateNotification("Running booking agent...")
            } else {
                LogManager.addLog("ERROR", "[REAL] Failed to start Go agent")
                updateNotification("Error: Failed to start agent")
                stopAgent()
            }
            
        } catch (e: Exception) {
            LogManager.addLog("ERROR", "Failed to build/start agent config: ${e.message}")
            updateNotification("Error: ${e.message}")
            stopAgent()
        }
    }
    
    /**
     * Note: simulateGoAgentRun() has been removed.
     * The service now uses the real Go agent via GoAgentWrapper.
     */
    
    /**
     * Stop the Go agent and the service.
     */
    fun stopAgent() {
        LogManager.addLog("INFO", "Stopping booking service")
        
        // Stop the Go agent via GoAgentWrapper
        if (GoAgentWrapper.isRunning()) {
            GoAgentWrapper.stop()
        }
        
        isRunning = false
        currentRun = null
        
        updateNotification("Stopped")
        
        // Delay stopping to allow user to see final status
        android.os.Handler(mainLooper).postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2000)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Booking agent status notifications"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Booking Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        LogManager.addLog("INFO", "Booking service destroyed")
    }
}
