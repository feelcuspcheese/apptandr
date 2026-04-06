package com.booking.bot.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun

/**
 * AlarmScheduler following TECHNICAL_SPEC.md section 6.1.
 * Schedules exact alarms using AlarmManager.setExactAndAllowWhileIdle for precise timing.
 */
class AlarmScheduler(private val context: Context) {
    
    companion object {
        /**
         * Safely parses duration strings (e.g., "30s", "1.5m") into milliseconds.
         */
        fun parseDurationToMillis(duration: String): Long {
            val s = duration.trim().lowercase()
            return try {
                when {
                    s.endsWith("ms") -> s.dropLast(2).toLongOrNull() ?: 0L
                    s.endsWith("s") -> (s.dropLast(1).toDoubleOrNull()?.times(1000))?.toLong() ?: 30000L
                    s.endsWith("m") -> (s.dropLast(1).toDoubleOrNull()?.times(60000))?.toLong() ?: 30000L
                    s.endsWith("h") -> (s.dropLast(1).toDoubleOrNull()?.times(3600000))?.toLong() ?: 30000L
                    else -> s.toLongOrNull() ?: 30000L
                }
            } catch (e: Exception) {
                30000L // fallback to 30s default
            }
        }
    }

    /**
     * Schedule a run at the specified dropTimeMillis minus the pre-warm offset.
     * This wakes the Android service early, allowing the Go Engine to establish 
     * TCP/TLS handshakes and spin-wait for the precision microsecond strike.
     */
    fun scheduleRun(run: ScheduledRun, preWarmOffsetMillis: Long) {
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
        
        // Calculate the early trigger time. Fallback to immediate execution if the offset pushes it into the past.
        val triggerTime = if (run.dropTimeMillis - preWarmOffsetMillis < System.currentTimeMillis()) {
            System.currentTimeMillis()
        } else {
            run.dropTimeMillis - preWarmOffsetMillis
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, 
                triggerTime, 
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP, 
                triggerTime, 
                pendingIntent
            )
        }
        
        // Log alarm scheduled event showing both trigger and execution targets
        val utcTime = java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString()
        val triggerUtcTime = java.time.Instant.ofEpochMilli(triggerTime).toString()
        LogManager.addLog("INFO", "Alarm scheduled for run ${run.id} to trigger early at $triggerUtcTime (target drop time: $utcTime)")
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
        
        // Log alarm cancelled event
        LogManager.addLog("INFO", "Alarm cancelled for run $runId")
    }
}
