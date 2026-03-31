package com.apptcheck.agent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.apptcheck.agent.model.ScheduledRun

/**
 * Scheduling layer following TECHNICAL_SPEC.md section 6.
 * Uses AlarmManager for exact-time triggers.
 */
class AlarmScheduler(private val context: Context) {
    
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    /**
     * Schedule a run at the specified time
     */
    fun scheduleRun(run: ScheduledRun) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("run_id", run.id)
            putExtra("site_key", run.siteKey)
            putExtra("museum_slug", run.museumSlug)
            putExtra("drop_time", run.dropTimeMillis)
            putExtra("mode", run.mode)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            run.id.hashCode(), 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Use setExactAndAllowWhileIdle for Android M+, else setExact
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, 
                run.dropTimeMillis, 
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP, 
                run.dropTimeMillis, 
                pendingIntent
            )
        }
    }
    
    /**
     * Cancel a scheduled run by ID
     */
    fun cancelRun(runId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            runId.hashCode(), 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
    
    /**
     * Cancel all scheduled runs
     */
    fun cancelAllRuns(runIds: List<String>) {
        runIds.forEach { cancelRun(it) }
    }
}
