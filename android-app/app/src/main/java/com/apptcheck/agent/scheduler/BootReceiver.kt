package com.apptcheck.agent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.data.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Broadcast receiver that re-schedules all alarms after device reboot.
 * Following TECHNICAL_SPEC.md section 6 and OVERVIEW.md acceptance criteria.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            LogManager.addLog("INFO", "Boot completed - rescheduling alarms")
            
            // Re-schedule all pending runs in background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val configManager = ConfigManager(context)
                    val alarmScheduler = AlarmScheduler(context)
                    val scheduledRuns = configManager.getScheduledRuns()
                    
                    val now = System.currentTimeMillis()
                    var rescheduledCount = 0
                    
                    scheduledRuns.forEach { run ->
                        // Only reschedule future runs
                        if (run.dropTimeMillis > now) {
                            alarmScheduler.scheduleRun(run)
                            rescheduledCount++
                            LogManager.addLog("INFO", "Rescheduled run ${run.id} for ${run.siteKey}/${run.museumSlug}")
                        }
                    }
                    
                    LogManager.addLog("INFO", "Rescheduled $rescheduledCount runs after boot")
                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to reschedule alarms after boot: ${e.message}")
                }
            }
        }
    }
}
