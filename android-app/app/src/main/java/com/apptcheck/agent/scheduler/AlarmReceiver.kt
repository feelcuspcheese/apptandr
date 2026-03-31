package com.apptcheck.agent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.service.BookingForegroundService

/**
 * Broadcast receiver that fires when a scheduled alarm triggers.
 * Following TECHNICAL_SPEC.md section 6 and OVERVIEW.md key flows.
 * Starts the ForegroundService which launches the Go agent.
 * 
 * Supports immediate start via "start_now" flag for Dashboard's Start Now button.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra("run_id") ?: return
        val siteKey = intent.getStringExtra("site_key") ?: return
        val museumSlug = intent.getStringExtra("museum_slug") ?: return
        val dropTimeMillis = intent.getLongExtra("drop_time", 0L)
        val mode = intent.getStringExtra("mode") ?: "alert"
        val startNow = intent.getBooleanExtra("start_now", false)
        
        LogManager.addLog("INFO", "Alarm triggered for run $runId at site $siteKey, museum $museumSlug${if (startNow) " (immediate start)" else ""}")
        
        // Start the foreground service to run the booking agent
        val serviceIntent = Intent(context, BookingForegroundService::class.java).apply {
            putExtra("run_id", runId)
            putExtra("site_key", siteKey)
            putExtra("museum_slug", museumSlug)
            putExtra("drop_time", dropTimeMillis)
            putExtra("mode", mode)
            putExtra("start_now", startNow)
        }
        
        // Start as foreground service (Android 8+ requires startForegroundService)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
