package com.booking.bot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.booking.bot.MainActivity
import com.booking.bot.data.*
import com.booking.bot.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import kotlin.collections.ArrayList

import mobile.MobileAgent

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 * 
 * Gold Standard Audit Enhancements:
 * 1. WakeLock: Deep Doze protection.
 * 2. Native Alerts: System drawer feedback with Calendar Integration.
 * 3. Polling Loop: Agent lifecycle management.
 * 4. DST-Aware Rescheduling: Uses Calendar arithmetic for 24h loops.
 * 5. Atomic Rescheduling: Prevents data loss during process death.
 * 6. Pre-flight Test Mode: Verifies library credentials on BiblioCommons.
 * 7. Calendar Enhancement (v1.5): Adds "Add to Calendar" action with friendly names.
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val ALERT_CHANNEL_ID = "booking_alerts" 
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"
        private const val TEST_ACTION = "com.booking.bot.TEST_CREDENTIALS"
        private const val WAKE_LOCK_TAG = "BookingBot::ExecutionWakeLock"

        // CG-05: Timeout constant - 10 minutes max run time
        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // v1.3 Feature 4: Live Status Flow for UI feedback
        private val _goStatus = MutableStateFlow("Idle")
        val goStatus: StateFlow<String> = _goStatus.asStateFlow()

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
                putStringArrayListExtra("pref_days", ArrayList(run.preferredDays))
                putStringArrayListExtra("pref_dates", ArrayList(run.preferredDates))
                putExtra("is_recurring", run.isRecurring)
                putExtra("remaining_occurrences", run.remainingOccurrences)
                run.endDateMillis?.let { putExtra("end_date_millis", it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * v1.4 Feature 1: Starts the service in a one-off test mode.
         */
        fun startTest(context: Context, siteKey: String, credentialId: String) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                action = TEST_ACTION
                putExtra("site_key", siteKey)
                putExtra("credential_id", credentialId)
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
    private var resolvedMuseumName = "" // v1.5 Friendly name cache for Calendar

    // v1.3 Feature 1 Tracking
    private var lastRunOutcome = "MISSED"
    private var lastRunMessage = "No slots found during the check window."

    /**
     * Parses JSON logs from the Go agent and routes them to LogManager.
     * v1.3: Also detects success/failure for the History Feature.
     */
    private fun onGoLog(jsonLog: String) {
        try {
            val json = org.json.JSONObject(jsonLog)
            val level = json.optString("level", "INFO")
            val message = json.optString("message", "")
            if (message.isNotEmpty()) {
                LogManager.addLog(level, message)

                // Outcome Detection for History (Feature 1)
                if (message.contains("Confirmed for", ignoreCase = true) || 
                    message.contains("Booking successful", ignoreCase = true) ||
                    message.contains("Notification sent", ignoreCase = true)) {
                    lastRunOutcome = "SUCCESS"
                    lastRunMessage = message
                } else if (message.contains("FATAL", ignoreCase = true) || 
                           message.contains("Booking failed", ignoreCase = true) ||
                           message.contains("Error sending ntfy", ignoreCase = true)) {
                    lastRunOutcome = "FAILED"
                    lastRunMessage = message
                }
            }
        } catch (e: Exception) {
            // Fallback for non-JSON or malformed output
            LogManager.addLog("INFO", jsonLog)
        }
    }

    /**
     * Updates the persistent notification with status from Go agent.
     * v1.3: Also updates the live status flow for Dashboard pulse.
     */
    private fun onGoStatus(status: String) {
        _goStatus.value = status
        updateNotification(status)
    }

    /**
     * Helper to extract date from Go success log for Calendar intents.
     * Message format: "Confirmed for YYYY-MM-DD ..."
     */
    private fun extractDateMillis(message: String): Long {
        return try {
            val regex = "\\d{4}-\\d{2}-\\d{2}".toRegex()
            val match = regex.find(message)?.value
            if (match != null) {
                val date = LocalDate.parse(match)
                date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else -1L
        } catch (e: Exception) { -1L }
    }

    /**
     * Triggers a native system notification for matches or fatal errors.
     * v1.5 Enhancement: Adds "Add to Calendar" button with friendly museum name.
     */
    private fun showNativeAlert(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
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

        // v1.5 Feature: Add Calendar Intent button on SUCCESS
        if (title.contains("SUCCESS", ignoreCase = true)) {
            val dateMillis = extractDateMillis(message)
            if (dateMillis != -1L) {
                val friendlyTitle = if (resolvedMuseumName.isNotEmpty()) {
                    "$resolvedMuseumName Visit : Pass confirmed"
                } else {
                    "Museum Visit : Pass confirmed"
                }

                val calIntent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, friendlyTitle)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dateMillis + (9 * 60 * 60 * 1000)) // Suggest 9am
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dateMillis + (17 * 60 * 60 * 1000))  // Suggest 5pm
                    putExtra(CalendarContract.Events.ALL_DAY, true)
                    putExtra(CalendarContract.Events.DESCRIPTION, "Auto-booked by Booking Bot.\n$message")
                }
                val calPi = PendingIntent.getActivity(this, 1, calIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(android.R.drawable.ic_menu_my_calendar, "Add to Calendar", calPi)
            }
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
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

            if (it.action == TEST_ACTION) {
                handleCredentialTest(it)
                return START_STICKY
            }

            val runId = it.getStringExtra("run_id") ?: return START_NOT_STICKY

            // Concurrency Check (CG-04)
            if (mobileAgent?.isRunning() == true) {
                LogManager.addLog("WARN", "Run $runId ignored – agent already busy")
                return START_NOT_STICKY
            }

            // Reset outcome tracking for this specific run
            lastRunOutcome = "MISSED"
            lastRunMessage = "No slots found during the check window."

            // High-Priority Execution Start: Acquire WakeLock
            wakeLock?.acquire(RUN_TIMEOUT_MS)
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true

            // Required import: launch
            serviceScope.launch {
                try {
                    val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                    val config = configManager.configFlow.first()
                    
                    // Retrieve full run object to access locked configurations and recursion settings
                    val run = config.scheduledRuns.find { it.id == runId }

                    if (run == null) {
                        LogManager.addLog("ERROR", "Scheduled run $runId not found in DataStore")
                        cleanupAndStop(runId)
                        return@launch
                    }

                    currentRun = run
                    // Resolve museum name for the Calendar Intent friendly title
                    resolvedMuseumName = config.admin.sites[run.siteKey]?.museums?.get(run.museumSlug)?.name ?: run.museumSlug
                    
                    _goStatus.value = "Starting"
                    LogManager.addLog("INFO", "Foreground service active for run ${run.id}")

                    val agentConfigJson = configManager.buildAgentConfig(run, config)

                    if (agentConfigJson == null) {
                        LogManager.addLog("ERROR", "Failed to build agent config – site/museum not found")
                        cleanupAndStop(runId)
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
                        cleanupAndStop(runId)
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
                    cleanupAndStop(runId)

                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Service Execution Error: ${e.message}")
                    cleanupAndStop(runId)
                }
            }
        }

        return START_STICKY
    }

    /**
     * v1.4 Feature 1: Performs a library portal login test.
     */
    private fun handleCredentialTest(intent: Intent) {
        val siteKey = intent.getStringExtra("site_key") ?: return
        val credId = intent.getStringExtra("credential_id") ?: return

        _isRunning.value = true
        _goStatus.value = "Testing Credentials"
        startForeground(NOTIFICATION_ID, createNotification("Testing Credentials..."))

        serviceScope.launch {
            try {
                val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                val config = configManager.configFlow.first()
                val testJson = configManager.buildTestConfig(siteKey, credId, config) ?: return@launch

                mobileAgent = MobileAgent()
                var testResult = "FAILED"
                
                mobileAgent?.setLogCallback { LogManager.addLog("INFO", it) }
                mobileAgent?.setStatusCallback { status ->
                    _goStatus.value = status
                    if (status.contains("VERIFIED", ignoreCase = true)) {
                        testResult = "VERIFIED"
                    }
                }

                mobileAgent?.start(testJson)
                while (mobileAgent?.isRunning() == true) { delay(500) }

                configManager.updateCredentialVerification(siteKey, credId, testResult)
                LogManager.addLog("INFO", "Credential test finished: $testResult")
            } finally {
                _isRunning.value = false
                _goStatus.value = "Idle"
                stopSelf()
            }
        }
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
            _goStatus.value = "Idle"
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
     * Final cleanup, Recursion handling (v1.2), and History Persistence (v1.3).
     */
    private suspend fun cleanupAndStop(runId: String) {
        _isRunning.value = false
        _goStatus.value = "Idle"
        mobileAgent = null
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        try {
            val configManager = ConfigManager.getInstance(this@BookingForegroundService)
            val config = configManager.configFlow.first()
            val run = currentRun ?: config.scheduledRuns.find { it.id == runId }

            if (run != null) {
                // v1.3 Feature 1: Persistence to History
                val site = config.admin.sites[run.siteKey]
                val museumName = site?.museums?.get(run.museumSlug)?.name ?: run.museumSlug
                
                val result = RunResult(
                    timestamp = System.currentTimeMillis(),
                    siteName = site?.name ?: run.siteKey,
                    museumName = museumName,
                    mode = run.mode,
                    status = lastRunOutcome,
                    message = lastRunMessage
                )
                configManager.addRunResult(result)

                // v1.2 Recurring Logic
                if (run.isRecurring) {
                    // DST-AWARE RESCHEDULING (v1.3 Fix):
                    // Use Calendar arithmetic to add exactly 1 day instead of raw milliseconds.
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = run.dropTimeMillis
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                    val nextDropTime = calendar.timeInMillis
                    
                    // Check stop conditions
                    val isExpiredByDate = run.endDateMillis?.let { nextDropTime > it } ?: false
                    val isExpiredByCount = run.remainingOccurrences == 1 // This run was the last one

                    if (isExpiredByDate || isExpiredByCount) {
                        configManager.removeScheduledRun(runId)
                        LogManager.add
