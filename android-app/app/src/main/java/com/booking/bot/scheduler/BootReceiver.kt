package com.booking.bot.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.ScheduledRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BootReceiver following TECHNICAL_SPEC.md section 6.3.
 * Restores all scheduled alarms after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO).launch {
                val configManager = ConfigManager.getInstance(context)
                val config = configManager.configFlow.first()
                val scheduler = AlarmScheduler(context)
                
                // Re-schedule only future runs (filter out past runs)
                val currentTime = System.currentTimeMillis()
                config.scheduledRuns.forEach { run: ScheduledRun ->
                    if (run.dropTimeMillis > currentTime) {
                        scheduler.scheduleRun(run)
                    }
                }
            }
        }
    }
}
