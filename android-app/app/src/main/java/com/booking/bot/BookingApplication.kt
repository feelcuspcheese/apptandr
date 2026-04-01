package com.booking.bot

import android.app.Application
import com.booking.bot.data.LogManager

/**
 * Application class for the Booking Bot app.
 * Initializes singleton components on app startup.
 */
class BookingApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize LogManager (section 7)
        LogManager.init(this)
    }
}
