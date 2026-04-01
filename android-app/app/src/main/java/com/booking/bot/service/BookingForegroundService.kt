package com.booking.bot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jetbrains.annotations.VisibleForTesting

// MobileAgent will be loaded from booking.aar at runtime
// This placeholder allows compilation when AAR is not present
actual class MobileAgent {
    private var running = false
    private var logCallback: ((String) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null

    fun start(configJson: String) {
        running = true
        logCallback?.invoke("{\"level\":\"INFO\",\"message\":\"Agent started with config: $configJson\"}")
        statusCallback?.invoke("Running")
    }

    fun stop() {
        running = false
        statusCallback?.invoke("Stopped")
    }

    fun isRunning(): Boolean = running

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }
}

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 *
 * Provides StateFlow<Boolean> for isRunning state that UI can observe.
 * 
 * CRITICAL FEATURES (per audit report):
 * - CG-04: Concurrency check - prevents multiple runs from starting simultaneously
 * - CG-01/CG-02: Polling loop - waits for agent completion before stopping
 * - CG-03: cleanupAndStop() - removes completed run from scheduledRuns
 * - CG-05: Timeout handling - forces cleanup after 10 minutes max
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"
        
        // CG-05: Timeout constant - 10 minutes max run time
        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Start the service with a scheduled run.
         */
        fun start(context: Context, run: ScheduledRun) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                putExtra("run_id", run.id)
                putExtra("site_key", run.siteKey)
                putExtra("museum_slug", run.museumSlug)
                putExtra("credential_id", run.credentialId)
                putExtra("drop_time", run.dropTimeMillis)
                putExtra("mode", run.mode)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                action = STOP_ACTION
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mobileAgent: MobileAgent? = null // Holds MobileAgent instance from AAR
    private var currentRun: ScheduledRun? = null // Track current run for cleanup

    /**
     * Callback for receiving log messages from the Go agent.
     * Following TECHNICAL_SPEC.md section 8: Log callback format expects JSON like
     * {"timestamp":123456,"level":"INFO","message":"Pre-warming..."}
     */
    private fun onGoLog(jsonLog: String) {
        // Parse JSON log from Go agent and add to LogManager
        try {
            val json = org.json.JSONObject(jsonLog)
            val level = json.optString("level", "INFO")
            val message = json.optString("message", "")
            if (message.isNotEmpty()) {
                LogManager.addLog(level, message)
            }
        } catch (e: Exception) {
            // If parsing fails, log the raw message as INFO
            LogManager.addLog("INFO", jsonLog)
        }
    }

    /**
     * Callback for receiving status updates from the Go agent.
     */
    private fun onGoStatus(status: String) {
        updateNotification(status)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == STOP_ACTION) {
                stopAgent()
                return START_NOT_STICKY
            }

            val run = ScheduledRun(
                id = it.getStringExtra("run_id") ?: return START_NOT_STICKY,
                siteKey = it.getStringExtra("site_key") ?: return START_NOT_STICKY,
                museumSlug = it.getStringExtra("museum_slug") ?: return START_NOT_STICKY,
                credentialId = it.getStringExtra("credential_id"),
                dropTimeMillis = it.getLongExtra("drop_time", 0),
                mode = it.getStringExtra("mode") ?: "alert"
            )

            // CG-04: Concurrency check - prevent multiple runs from starting simultaneously
            if (mobileAgent?.isRunning() == true) {
                LogManager.addLog("WARN", "Run ${run.id} ignored – another run is already active")
                stopSelf()
                return START_NOT_STICKY
            }

            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true
            currentRun = run // Track the current run for cleanup

            serviceScope.launch {
                try {
                    // Load current config
                    val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                    val config = configManager.configFlow.first()

                    // Build JSON for Go agent
                    val agentConfigJson = configManager.buildAgentConfig(run, config)

                    // Initialize MobileAgent from AAR (section 8 of TECHNICAL_SPEC.md)
                    mobileAgent = MobileAgent()

                    // Set up log callback to receive JSON logs from Go agent
                    mobileAgent?.setLogCallback { jsonLog: String ->
                        onGoLog(jsonLog)
                    }

                    // Set up status callback to update notification
                    mobileAgent?.setStatusCallback { status: String ->
                        onGoStatus(status)
                    }

                    // Start the Go agent with the configuration JSON
                    mobileAgent?.start(agentConfigJson)

                    LogManager.addLog("INFO", "Go agent started successfully for run ${run.id}")
                    updateNotification("Running...")

                    // CG-01/CG-02: Polling loop - wait for agent to complete
                    // CG-05: Timeout handling - max 10 minutes
                    val startTime = System.currentTimeMillis()
                    while (mobileAgent?.isRunning() == true) {
                        delay(1000)
                        
                        // Check for timeout
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > RUN_TIMEOUT_MS) {
                            LogManager.addLog("WARN", "Run ${run.id} timed out after ${RUN_TIMEOUT_MS / 1000}s - forcing cleanup")
                            mobileAgent?.stop()
                            break
                        }
                    }

                    LogManager.addLog("INFO", "Run ${run.id} completed")
                    
                    // CG-03: Cleanup and stop - remove run from scheduledRuns
                    cleanupAndStop(run.id)

                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to run agent: ${e.message}")
                    updateNotification("Error: ${e.message}")
                    cleanupAndStop(run.id)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAgent()
    }

    /**
     * Stop the Go agent and clean up.
     * Note: This is for manual stop via notification action.
     * For automatic cleanup after run completion, use cleanupAndStop().
     */
    private fun stopAgent() {
        serviceScope.launch {
            try {
                // Stop the Go agent (section 8 of TECHNICAL_SPEC.md)
                mobileAgent?.stop()
                mobileAgent = null

                LogManager.addLog("INFO", "Agent stopped manually")
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "Error stopping agent: ${e.message}")
            } finally {
                _isRunning.value = false
                currentRun?.let { run ->
                    // Also cleanup if user manually stops
                    ConfigManager.getInstance(this@BookingForegroundService).removeScheduledRun(run.id)
                    LogManager.addLog("INFO", "Run ${run.id} removed from schedule (manual stop)")
                }
                currentRun = null
                stopSelf()
            }
        }
    }

    /**
     * CG-03: Cleanup and stop after run completion.
     * Removes the completed run from scheduledRuns and stops the service.
     * Called automatically when the agent finishes (success, error, or timeout).
     */
    private suspend fun cleanupAndStop(runId: String) {
        _isRunning.value = false
        mobileAgent = null
        
        try {
            val configManager = ConfigManager.getInstance(this@BookingForegroundService)
            configManager.removeScheduledRun(runId)
            LogManager.addLog("INFO", "Run $runId removed from schedule (completed)")
        } catch (e: Exception) {
            LogManager.addLog("ERROR", "Failed to remove run $runId from schedule: ${e.message}")
        } finally {
            currentRun = null
            stopSelf()
        }
    }

    /**
     * Create notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Booking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of booking agent"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create or update the foreground service notification.
     */
    private fun createNotification(status: String): android.app.Notification {
        val stopIntent = Intent(this, BookingForegroundService::class.java).apply {
            action = STOP_ACTION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Booking Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    /**
     * Update the notification with new status.
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
