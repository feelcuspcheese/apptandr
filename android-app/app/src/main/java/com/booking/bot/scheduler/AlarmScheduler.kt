package com.booking.bot.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.booking.bot.data.ScheduledRun

/**
 * AlarmScheduler following TECHNICAL_SPEC.md section 6.1.
 * Schedules exact alarms using AlarmManager.setExactAndAllowWhileIdle for precise timing.
 */
class AlarmScheduler(private val context: Context) {
    
    /**
     * Schedule a run at the specified dropTimeMillis.
     * Uses setExactAndAllowWhileIdle on API 23+ for exact timing even in Doze mode.
     */
    fun scheduleRun(run: ScheduledRun) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("run_id", run.id)
            putExtra("site_key", run.siteKey)
            putExtra("museum_slug", run.museumSlug)
            putExtra("credential_id", run.credentialId)
            putExtra("drop_time", run.dropTimeMillis)
            putExtra("mode", run.mode)
            putExtra("timezone", run.timezone)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            run.id.hashCode(), 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
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
     * Cancel a scheduled run by ID.
     */
    fun cancelRun(runId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            runId.hashCode(), 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }
}
