package com.booking.bot.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.booking.bot.data.ScheduledRun
import com.booking.bot.service.BookingForegroundService

/**
 * AlarmReceiver following TECHNICAL_SPEC.md section 6.2.
 * BroadcastReceiver that triggers when a scheduled alarm fires.
 * Starts the BookingForegroundService with the scheduled run data.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val run = ScheduledRun(
            id = intent.getStringExtra("run_id") ?: return,
            siteKey = intent.getStringExtra("site_key") ?: return,
            museumSlug = intent.getStringExtra("museum_slug") ?: return,
            credentialId = intent.getStringExtra("credential_id"),
            dropTimeMillis = intent.getLongExtra("drop_time", 0),
            mode = intent.getStringExtra("mode") ?: "alert",
            timezone = intent.getStringExtra("timezone") ?: java.util.TimeZone.getDefault().id
        )
        
        BookingForegroundService.start(context, run)
    }
}
