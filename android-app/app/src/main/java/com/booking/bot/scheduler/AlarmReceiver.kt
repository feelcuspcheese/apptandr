package com.booking.bot.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun
import com.booking.bot.service.BookingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AlarmReceiver following TECHNICAL_SPEC.md section 6.2.
 * BroadcastReceiver that triggers when a scheduled alarm fires.
 * 
 * v1.3 Enhancement: 
 * - Master Switch Logic: Checks if schedules are paused before starting the service.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra("run_id") ?: return
        val siteKey = intent.getStringExtra("site_key") ?: return
        val museumSlug = intent.getStringExtra("museum_slug") ?: return
        val credentialId = intent.getStringExtra("credential_id")
        val dropTime = intent.getLongExtra("drop_time", 0)
        val mode = intent.getStringExtra("mode") ?: "alert"
        val timezone = intent.getStringExtra("timezone") ?: java.util.TimeZone.getDefault().id
        
        val run = ScheduledRun(
            id = runId,
            siteKey = siteKey,
            museumSlug = museumSlug,
            credentialId = credentialId,
            dropTimeMillis = dropTime,
            mode = mode,
            timezone = timezone
        )

        // Feature 3: Master Switch Verification
        // We perform this check here to prevent the Foreground Service from even starting if paused.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configManager = ConfigManager.getInstance(context)
                val config = configManager.configFlow.first()
                
                if (config.general.isPaused) {
                    LogManager.addLog("INFO", "Master Switch is PAUSED. Skipping run $runId (${run.museumSlug})")
                    return@launch
                }

                LogManager.addLog("INFO", "Alarm triggered for run $runId. Starting service.")
                BookingForegroundService.start(context, run)
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "AlarmReceiver processing error: ${e.message}")
            }
        }
    }
}
