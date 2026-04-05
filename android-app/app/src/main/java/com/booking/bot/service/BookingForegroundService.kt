package com.booking.bot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.booking.bot.MainActivity
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

import mobile.MobileAgent

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 * 
 * Deep Audit Enhancements:
 * 1. WakeLock: Prevents CPU sleep during the high-precision Strike phase.
 * 2. Native Alerts: Pushes high-priority notifications to the Android drawer.
 * 3. Polling Loop: Monitors agent life and handles cleanup on finish or stop.
 * 4. Coroutine Safety: Uses explicit Dispatchers and SupervisorJob for stability.
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val ALERT_CHANNEL_ID = "booking_alerts" 
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"
        private const val WAKE_LOCK_TAG = "BookingBot::ExecutionWakeLock"

        // CG-05: Timeout constant - 10 minutes max run time
        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Starts the service with the intent containing run details.
         */
        fun start(context: Context, run: ScheduledRun) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                putExtra("run_id", run.id)
                putExtra("site_key", run.siteKey)
                putExtra("museum_slug", run.museumSlug)
                putExtra("credential_id", run.credentialId)
                putExtra("drop_time", run.dropTimeMillis)
                putExtra("mode", run.mode)
                putExtra("timezone", run.timezone)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Sends a stop intent to the running service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                action = STOP_ACTION
            }
            context.startService(intent)
        }
    }

    // Required imports used here: CoroutineScope, Dispatchers, SupervisorJob
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mobileAgent: MobileAgent? = null
    private var currentRun: ScheduledRun? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Parses JSON logs from the Go agent and routes them to LogManager.
     */
    private fun onGoLog(jsonLog: String) {
        try {
            val json = org.json.JSONObject(jsonLog)
            val level = json.optString("level", "INFO")
            val message = json.optString("message", "")
            if (message.isNotEmpty()) {
                LogManager.addLog(level, message)
            }
        } catch (e: Exception) {
            // Fallback for non-JSON or malformed output
            LogManager.addLog("INFO", jsonLog)
        }
    }

    /**
     * Updates the persistent notification with status from Go agent.
     */
    private fun onGoStatus(status: String) {
        updateNotification(status)
    }

    /**
     * Triggers a native system notification for matches or fatal errors.
     * Required import: MainActivity
     */
    private fun showNativeAlert(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) 
            .setOngoing(false)   
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // PowerManager used for WakeLock management (Deep Doze protection)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

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
                mode = it.getStringExtra("mode") ?: "alert",
                timezone = it.getStringExtra("timezone") ?: java.util.TimeZone.getDefault().id
            )

            // Concurrency Check (CG-04)
            if (mobileAgent?.isRunning() == true) {
                LogManager.addLog("WARN", "Run ${run.id} ignored – agent already busy")
                return START_NOT_STICKY
            }

            // High-Priority Execution Start: Acquire WakeLock
            wakeLock?.acquire(RUN_TIMEOUT_MS)
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true
            currentRun = run

            // Required import: launch
            serviceScope.launch {
                try {
                    LogManager.addLog("INFO", "Foreground service started for run ${run.id}")

                    val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                    val config = configManager.configFlow.first()

                    val agentConfigJson = configManager.buildAgentConfig(run, config)

                    if (agentConfigJson == null) {
                        LogManager.addLog("ERROR", "Failed to build agent config – site/museum not found")
                        cleanupAndStop(run.id)
                        return@launch
                    }

                    mobileAgent = MobileAgent()

                    mobileAgent?.setLogCallback { jsonLog: String ->
                        onGoLog(jsonLog)
                    }
                    mobileAgent?.setStatusCallback { status: String ->
                        onGoStatus(status)
                    }
                    // Setup the bridge for native alerts
                    mobileAgent?.setNotifyCallback { title: String, message: String ->
                        showNativeAlert(title, message)
                    }

                    LogManager.addLog("INFO", "Attempting to start Go agent")
                    val started = mobileAgent?.start(agentConfigJson) ?: false
                    
                    if (!started) {
                        LogManager.addLog("ERROR", "Go agent rejected the start command")
                        cleanupAndStop(run.id)
                        return@launch
                    }

                    LogManager.addLog("INFO", "Go agent is now active")
                    updateNotification("Running...")

                    // Polling loop for agent completion (CG-02)
                    // Required imports: delay
                    val startTime = System.currentTimeMillis()
                    while (mobileAgent?.isRunning() == true) {
                        delay(1000)
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > RUN_TIMEOUT_MS) {
                            LogManager.addLog("WARN", "Run timed out after 10m – forcing cleanup")
                            mobileAgent?.stop()
                            break
                        }
                    }

                    LogManager.addLog("INFO", "Run ${run.id} finished")
                    cleanupAndStop(run.id)

                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Service Execution Error: ${e.message}")
                    cleanupAndStop(run.id)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopAgent()
        super.onDestroy()
    }

    /**
     * Manual stop triggered by user from Dashboard or Notification.
     */
    private fun stopAgent() {
        serviceScope.launch {
            _isRunning.value = false
            try {
                mobileAgent?.stop()
                mobileAgent = null
                LogManager.addLog("INFO", "Agent stopped manually by user")
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "Error during manual stop: ${e.message}")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                currentRun?.let { run ->
                    ConfigManager.getInstance(this@BookingForegroundService).removeScheduledRun(run.id)
                    LogManager.addLog("INFO", "Run ${run.id} purged from schedule")
                }
                currentRun = null
                stopSelf()
            }
        }
    }

    /**
     * Final cleanup of WakeLocks, notification state, and DataStore.
     */
    private suspend fun cleanupAndStop(runId: String) {
        _isRunning.value = false
        mobileAgent = null
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        LogManager.addLog("INFO", "Performing final cleanup for run $runId")

        try {
            val configManager = ConfigManager.getInstance(this@BookingForegroundService)
            configManager.removeScheduledRun(runId)
        } catch (e: Exception) {
            LogManager.addLog("ERROR", "Failed to remove run from DataStore: ${e.message}")
        } finally {
            currentRun = null
            stopSelf()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 1. Channel for ongoing service status
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Booking Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Status monitoring for active runs" }
            nm.createNotificationChannel(serviceChannel)

            // 2. Channel for high-priority alerts
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Booking Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for found slots and booking results"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(status: String): android.app.Notification {
        val stopIntent = Intent(this, BookingForegroundService::class.java).apply {
            action = STOP_ACTION
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Booking Agent Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(status))
    }
}
