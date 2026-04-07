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
 * 
 * v1.3 Enhancement: 
 * - Master Switch Logic: Verifies pause state.
 * - Recursion & Preference Extraction: Reconstructs the full locked ScheduledRun.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra("run_id") ?: return
        
        val run = ScheduledRun(
            id = runId,
            siteKey = intent.getStringExtra("site_key") ?: return,
            museumSlug = intent.getStringExtra("museum_slug") ?: return,
            credentialId = intent.getStringExtra("credential_id"),
            dropTimeMillis = intent.getLongExtra("drop_time", 0),
            mode = intent.getStringExtra("mode") ?: "alert",
            timezone = intent.getStringExtra("timezone") ?: java.util.TimeZone.getDefault().id,
            // v1.3 Extraction of locked preferences
            preferredDays = intent.getStringArrayListExtra("pref_days") ?: emptyList(),
            preferredDates = intent.getStringArrayListExtra("pref_dates") ?: emptyList(),
            // v1.2 Extraction of recursion logic
            isRecurring = intent.getBooleanExtra("is_recurring", false),
            remainingOccurrences = intent.getIntExtra("remaining_occurrences", 0),
            endDateMillis = if (intent.hasExtra("end_date_millis")) intent.getLongExtra("end_date_millis", 0) else null
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configManager = ConfigManager.getInstance(context)
                val config = configManager.configFlow.first()
                
                // Feature 3: Master Switch check
                if (config.general.isPaused) {
                    LogManager.addLog("INFO", "System PAUSED. Skipping run $runId")
                    return@launch
                }

                LogManager.addLog("INFO", "Alarm triggered for run $runId. Waking Go agent.")
                BookingForegroundService.start(context, run)
            } catch (e: Exception) {
                LogManager.addLog("ERROR", "AlarmReceiver processing error: ${e.message}")
            }
        }
    }
}
