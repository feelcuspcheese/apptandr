package com.booking.bot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.lifecycle.LifecycleService
import androidx.core.app.NotificationCompat
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.jetbrains.annotations.VisibleForTesting
import booking.MobileAgent

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 *
 * Provides StateFlow<Boolean> for isRunning state that UI can observe.
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"

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

            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true

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
                    mobileAgent?.setLogCallback { jsonLog ->
                        onGoLog(jsonLog)
                    }
                    
                    // Set up status callback to update notification
                    mobileAgent?.setStatusCallback { status ->
                        onGoStatus(status)
                    }
                    
                    // Start the Go agent with the configuration JSON
                    mobileAgent?.start(agentConfigJson)

                    LogManager.addLog("INFO", "Go agent started successfully")
                    updateNotification("Running...")

                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to start agent: ${e.message}")
                    updateNotification("Error: ${e.message}")
                    stopAgent()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAgent()
    }

    /**
     * Stop the Go agent and clean up.
     */
    private fun stopAgent() {
        serviceScope.launch {
            try {
                // Stop the Go agent (section 8 of TECHNICAL_SPEC.md)
                mobileAgent?.stop()
                mobileAgent = null

                LogManager.addLog("INFO", "Agent stopped")
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "Error stopping agent: ${e.message}")
            } finally {
                _isRunning.value = false
                stopSelf()
            }
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
