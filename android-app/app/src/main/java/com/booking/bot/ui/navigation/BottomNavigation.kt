package com.booking.bot.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation items for BottomNavigation following TECHNICAL_SPEC.md section 5.
 * Four tabs: Dashboard, Config, Schedule, Logs
 */
sealed class BottomNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Dashboard : BottomNavItem(
        route = "dashboard",
        title = "Dashboard",
        icon = Icons.Filled.Home
    )
    
    object Config : BottomNavItem(
        route = "config",
        title = "Config",
        icon = Icons.Filled.Settings
    )
    
    object Schedule : BottomNavItem(
        route = "schedule",
        title = "Schedule",
        icon = Icons.Filled.CalendarToday
    )
    
    object Logs : BottomNavItem(
        route = "logs",
        title = "Logs",
        icon = Icons.Filled.List
    )
    
    companion object {
        val items = listOf(Dashboard, Config, Schedule, Logs)
    }
}
