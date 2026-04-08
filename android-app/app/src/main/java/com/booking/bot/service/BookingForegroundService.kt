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
import java.util.*

import mobile.MobileAgent

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 * 
 * Gold Standard Audit Enhancements:
 * 1. WakeLock: Deep Doze protection.
 * 2. Native Alerts: System drawer feedback.
 * 3. Polling Loop: Agent lifecycle management.
 * 4. DST-Aware Rescheduling: Uses Calendar arithmetic for 24h loops (v1.3 Fix).
 * 5. Atomic Rescheduling: Prevents data loss during process death (v1.3 Fix).
 * 6. Pre-flight Test Mode (v1.4): Verifies library credentials on BiblioCommons.
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val ALERT_CHANNEL_ID = "booking_alerts" 
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"
        private const val TEST_ACTION = "com.booking.bot.TEST_CREDENTIALS"
        private const val WAKE_LOCK_TAG = "BookingBot::ExecutionWakeLock"

        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _goStatus = MutableStateFlow("Idle")
        val goStatus: StateFlow<String> = _goStatus.asStateFlow()

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

        fun stop(context: Context) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                action = STOP_ACTION
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mobileAgent: MobileAgent? = null
    private var currentRun: ScheduledRun? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastRunOutcome = "MISSED"
    private var lastRunMessage = "No slots found during the check window."

    private fun onGoLog(jsonLog: String) {
        try {
            val json = org.json.JSONObject(jsonLog)
            val level = json.optString("level", "INFO")
            val message = json.optString("message", "")
            if (message.isNotEmpty()) {
                LogManager.addLog(level, message)
                if (message.contains("Confirmed for", ignoreCase = true) || 
                    message.contains("Booking successful", ignoreCase = true) ||
                    message.contains("Notification sent", ignoreCase = true)) {
                    lastRunOutcome = "SUCCESS"
                    lastRunMessage = message
                } else if (message.contains("FATAL", ignoreCase = true) || 
                           message.contains("Booking failed", ignoreCase = true)) {
                    lastRunOutcome = "FAILED"
                    lastRunMessage = message
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("INFO", jsonLog)
        }
    }

    private fun onGoStatus(status: String) {
        _goStatus.value = status
        updateNotification(status)
    }

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
            if (mobileAgent?.isRunning() == true) {
                LogManager.addLog("WARN", "Run $runId ignored – agent already busy")
                return START_NOT_STICKY
            }

            lastRunOutcome = "MISSED"
            lastRunMessage = "No slots found during the check window."

            wakeLock?.acquire(RUN_TIMEOUT_MS)
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true

            serviceScope.launch {
                try {
                    val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                    val config = configManager.configFlow.first()
                    val run = config.scheduledRuns.find { it.id == runId }

                    if (run == null) {
                        LogManager.addLog("ERROR", "Run $runId not found in DataStore")
                        cleanupAndStop(runId)
                        return@launch
                    }

                    currentRun = run
                    _goStatus.value = "Starting"

                    val agentConfigJson = configManager.buildAgentConfig(run, config)
                    if (agentConfigJson == null) {
                        LogManager.addLog("ERROR", "Failed to build agent config for $runId")
                        cleanupAndStop(runId)
                        return@launch
                    }

                    mobileAgent = MobileAgent()
                    mobileAgent?.setLogCallback { onGoLog(it) }
                    mobileAgent?.setStatusCallback { onGoStatus(it) }
                    mobileAgent?.setNotifyCallback { t, m -> showNativeAlert(t, m) }

                    val started = mobileAgent?.start(agentConfigJson) ?: false
                    if (!started) {
                        LogManager.addLog("ERROR", "Go agent rejected the start command")
                        cleanupAndStop(runId)
                        return@launch
                    }

                    val startTime = System.currentTimeMillis()
                    while (mobileAgent?.isRunning() == true) {
                        delay(1000)
                        if (System.currentTimeMillis() - startTime > RUN_TIMEOUT_MS) {
                            LogManager.addLog("WARN", "Run timed out after 10m")
                            mobileAgent?.stop()
                            break
                        }
                    }

                    cleanupAndStop(runId)

                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Execution Error: ${e.message}")
                    cleanupAndStop(runId)
                }
            }
        }
        return START_STICKY
    }

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

    private fun stopAgent() {
        serviceScope.launch {
            _isRunning.value = false
            _goStatus.value = "Idle"
            try {
                mobileAgent?.stop()
                mobileAgent = null
                LogManager.addLog("INFO", "Agent stopped manually")
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "Manual stop error: ${e.message}")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                currentRun?.let { run ->
                    ConfigManager.getInstance(this@BookingForegroundService).removeScheduledRun(run.id)
                }
                currentRun = null
                stopSelf()
            }
        }
    }

    private suspend fun cleanupAndStop(runId: String) {
        _isRunning.value = false
        _goStatus.value = "Idle"
        mobileAgent = null
        if (wakeLock?.isHeld == true) { wakeLock?.release() }

        try {
            val configManager = ConfigManager.getInstance(this@BookingForegroundService)
            val config = configManager.configFlow.first()
            val run = currentRun ?: config.scheduledRuns.find { it.id == runId }

            if (run != null) {
                val site = config.admin.sites[run.siteKey]
                val museum = site?.museums?.get(run.museumSlug)
                
                configManager.addRunResult(RunResult(
                    timestamp = System.currentTimeMillis(),
                    siteName = site?.name ?: run.siteKey,
                    museumName = museum?.name ?: run.museumSlug,
                    mode = run.mode,
                    status = lastRunOutcome,
                    message = lastRunMessage
                ))

                if (run.isRecurring) {
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = run.dropTimeMillis
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                    val nextDropTime = calendar.timeInMillis
                    
                    val isExpiredByDate = run.endDateMillis?.let { nextDropTime > it } ?: false
                    val isExpiredByCount = run.remainingOccurrences == 1 

                    if (isExpiredByDate || isExpiredByCount) {
                        configManager.removeScheduledRun(runId)
                        LogManager.addLog("INFO", "Recurring run $runId limit reached. Purged.")
                    } else {
                        val updatedRun = run.copy(
                            dropTimeMillis = nextDropTime,
                            remainingOccurrences = if (run.remainingOccurrences > 0) run.remainingOccurrences - 1 else 0
                        )
                        configManager.rescheduleRecurringRun(runId, updatedRun)
                        
                        val offset = AlarmScheduler.parseDurationToMillis(config.general.preWarmOffset)
                        AlarmScheduler(this@BookingForegroundService).scheduleRun(updatedRun, offset)
                        
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextDropTime))
                        LogManager.addLog("INFO", "Recurring run $runId rescheduled for tomorrow at $timeStr")
                    }
                } else {
                    configManager.removeScheduledRun(runId)
                    LogManager.addLog("INFO", "Run $runId complete. History saved.")
                }
            } else {
                configManager.removeScheduledRun(runId)
            }
        } catch (e: Exception) {
            LogManager.addLog("ERROR", "Cleanup error: ${e.message}")
        } finally {
            currentRun = null
            stopSelf()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Booking Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(serviceChannel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Booking Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(status: String): android.app.Notification {
        val stopIntent = Intent(this, BookingForegroundService::class.java).apply {
            action = STOP_ACTION
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
