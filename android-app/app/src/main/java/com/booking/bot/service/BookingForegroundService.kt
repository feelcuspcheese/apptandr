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

import mobile.MobileAgent

/**
 * BookingForegroundService following TECHNICAL_SPEC.md section 6.4.
 * Runs the Go agent as a foreground service with persistent notification.
 *
 * Provides StateFlow<Boolean> for isRunning state that UI can observe.
 *
 * CRITICAL FEATURES (per audit report):
 * - CG-04: Concurrency check  – prevents multiple runs from starting simultaneously
 * - CG-01/CG-02: Polling loop – waits for agent completion before stopping
 * - CG-03: cleanupAndStop()  – removes completed run from scheduledRuns
 * - CG-05: Timeout handling  – forces cleanup after 10 minutes max
 *
 * -----------------------------------------------------------------------
 * FIX (Bug 1a) – App freeze / ANR when agent runs
 * -----------------------------------------------------------------------
 * Root cause:
 *   serviceScope used Dispatchers.Main. This caused every operation in the
 *   launch{} block — including JNI calls (mobileAgent.start / isRunning /
 *   stop) and LogManager.addLog() (which writes to disk) — to execute on
 *   the Android main thread.
 *
 *   Two combined effects produced the freeze:
 *
 *   1. mobileAgent?.isRunning() is a JNI call made from the main thread
 *      every second. JNI calls are synchronous — the main thread is
 *      suspended inside the JVM→Go boundary for the duration. While Go's
 *      mutex operations are fast, the Go runtime GC or goroutine scheduler
 *      can introduce multi-millisecond delays. Accumulated over the run
 *      lifetime this degrades main-thread responsiveness.
 *
 *   2. mobileAgent?.stop() calls Go's Stop() → agent.Stop() → a.wg.Wait()
 *      which is a BLOCKING call that waits for all agent goroutines to
 *      finish. Running this on the main thread caused an ANR whenever Stop
 *      was triggered (manual or timeout), freezing the UI until Go's
 *      goroutines drained — potentially several seconds.
 *
 * Fix: change serviceScope dispatcher to Dispatchers.IO. All JNI calls and
 * suspend operations now run on the IO thread pool. The main thread remains
 * free for Compose rendering, navigation, and input handling.
 *
 * MutableStateFlow.value assignments (_isRunning.value = …) are thread-safe
 * and do not require Dispatchers.Main. stopSelf() is also safe from any
 * thread (internally posts to the Service's main-thread handler).
 * -----------------------------------------------------------------------
 */
class BookingForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "booking_service"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_ACTION = "com.booking.bot.STOP_AGENT"

        // CG-05: Timeout constant - 10 minutes max run time
        private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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

        fun stop(context: Context) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                action = STOP_ACTION
            }
            context.startService(intent)
        }
    }

    // -------------------------------------------------------------------------
    // FIX: Dispatchers.IO replaces Dispatchers.Main.
    // See class-level KDoc above for full explanation.
    // -------------------------------------------------------------------------
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mobileAgent: MobileAgent? = null
    private var currentRun: ScheduledRun? = null

    /**
     * Callback for receiving log messages from the Go agent (called from Go goroutine threads).
     * LogManager.addLog() is thread-safe; see LogManager for the full threading contract.
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
            LogManager.addLog("INFO", jsonLog)
        }
    }

    /**
     * Callback for receiving status updates from the Go agent (called from Go goroutine threads).
     * NotificationManager.notify() is thread-safe on Android.
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
                mode = it.getStringExtra("mode") ?: "alert",
                timezone = it.getStringExtra("timezone") ?: java.util.TimeZone.getDefault().id
            )

            // CG-04: Concurrency check — must run on the main thread before startForeground
            if (mobileAgent?.isRunning() == true) {
                LogManager.addLog("WARN", "Run ${run.id} ignored – another run is already active")
                stopSelf()
                return START_NOT_STICKY
            }

            // startForeground() MUST be called on the main thread (here in onStartCommand)
            // before the coroutine launches. This satisfies Android O's 5-second rule.
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            _isRunning.value = true
            currentRun = run

            // All heavy work (config I/O, JNI, polling) now runs on Dispatchers.IO.
            serviceScope.launch {
                try {
                    LogManager.addLog("INFO", "Foreground service started for run ${run.id}")

                    val configManager = ConfigManager.getInstance(this@BookingForegroundService)
                    val config = configManager.configFlow.first()

                    val agentConfigJson = configManager.buildAgentConfig(run, config)

                    if (agentConfigJson == null) {
                        LogManager.addLog("ERROR", "Failed to build agent config for run ${run.id} – site/museum/credential not found")
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

                    LogManager.addLog("INFO", "Attempting to start Go agent for run ${run.id}")
                    // FIX (Bug 2): Check return value of start() and cleanup immediately on failure
                    val startedSuccessfully = mobileAgent?.start(agentConfigJson)
                    if (startedSuccessfully != true) {
                        LogManager.addLog("ERROR", "Go agent failed to start for run ${run.id}")
                        cleanupAndStop(run.id)
                        return@launch
                    }

                    LogManager.addLog("INFO", "Go agent started successfully for run ${run.id}")
                    updateNotification("Running...")

                    // CG-01/CG-02: Polling loop on IO thread — main thread stays free.
                    // CG-05: Timeout after 10 minutes.
                    val startTime = System.currentTimeMillis()
                    while (mobileAgent?.isRunning() == true) {
                        delay(1000)
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > RUN_TIMEOUT_MS) {
                            LogManager.addLog("WARN", "Run ${run.id} timed out after ${RUN_TIMEOUT_MS / 1000}s – forcing cleanup")
                            // mobileAgent.stop() calls a.wg.Wait() which blocks.
                            // On Dispatchers.IO this blocks an IO thread, not the main thread.
                            mobileAgent?.stop()
                            break
                        }
                    }

                    LogManager.addLog("INFO", "Run ${run.id} completed")
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

    override fun onDestroy() {
        super.onDestroy()
        stopAgent()
    }

    /**
     * Stop the Go agent and clean up (manual stop path via notification action).
     * Runs on Dispatchers.IO so mobileAgent.stop() → a.wg.Wait() does NOT block main thread.
     */
    private fun stopAgent() {
        serviceScope.launch {
            try {
                // mobileAgent.stop() calls Go Stop() → a.wg.Wait() which is BLOCKING.
                // Running on IO thread is safe and expected here.
                mobileAgent?.stop()
                mobileAgent = null
                LogManager.addLog("INFO", "Agent stopped manually")
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "Error stopping agent: ${e.message}")
            } finally {
                _isRunning.value = false
                currentRun?.let { run ->
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
     */
    private suspend fun cleanupAndStop(runId: String) {
        _isRunning.value = false
        mobileAgent = null

        LogManager.addLog("INFO", "Foreground service stopped for run $runId")

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Booking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows status of booking agent" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
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
            .setContentTitle("Booking Agent")
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
