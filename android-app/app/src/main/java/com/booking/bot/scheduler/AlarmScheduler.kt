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
 * 
 * v1.3 Enhancement: Passes recursion and locked preference data through the Intent.
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
            
            // v1.3 Feature: Pass locked preferred lists
            putStringArrayListExtra("pref_days", ArrayList(run.preferredDays))
            putStringArrayListExtra("pref_dates", ArrayList(run.preferredDates))
            
            // v1.2 Feature: Pass recursion state
            putExtra("is_recurring", run.isRecurring)
            putExtra("remaining_occurrences", run.remainingOccurrences)
            if (run.endDateMillis != null) {
                putExtra("end_date_millis", run.endDateMillis)
            }
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            run.id.hashCode(), 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
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
        
        val utcTime = java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString()
        LogManager.addLog("INFO", "Alarm set for run ${run.id} (Target: $utcTime). Recurring: ${run.isRecurring}")
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
        LogManager.addLog("INFO", "Alarm cancelled for run $runId")
    }
}
