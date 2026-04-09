package com.booking.bot

import android.app.Application
import com.booking.bot.data.LogManager

/**
 * Application class for the Booking Bot app.
 * Initializes singleton components on app startup.
 * 
 * Audit Fix: This file was previously "bleeding" the ForegroundService code. 
 * It has been restored to its proper state to resolve the "Redeclaration" build error.
 */
class BookingApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize LogManager (section 7)
        LogManager.init(this)
    }
}
