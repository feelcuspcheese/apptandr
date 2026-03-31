package com.apptcheck.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.ui.screens.DashboardScreen
import com.apptcheck.agent.ui.screens.UserConfigScreen
import com.apptcheck.agent.ui.screens.AdminConfigScreen
import com.apptcheck.agent.ui.screens.ScheduleScreen
import com.apptcheck.agent.ui.screens.LogsScreen

/**
 * Main Activity - entry point for the app.
 * Sets up Jetpack Compose with BottomNavigation and NavHost.
 * Following TECHNICAL_SPEC.md section 7.1 Navigation.
 */
class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            LogManager.addLog("INFO", "Notification permission granted")
        } else {
            LogManager.addLog("WARN", "Notification permission denied")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize LogManager
        LogManager.init(applicationContext)
        LogManager.addLog("INFO", "App started")
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = MaterialTheme.colorScheme.primary,
                    secondary = MaterialTheme.colorScheme.secondary,
                    tertiary = MaterialTheme.colorScheme.tertiary
                )
            ) {
                AppointmentAgentApp()
            }
        }
    }
}

@Composable
fun AppointmentAgentApp() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    NavigationItem("Dashboard", Icons.Filled.Home),
                    NavigationItem("User Config", Icons.Filled.Settings),
                    NavigationItem("Admin Config", Icons.Filled.AdminPanelSettings),
                    NavigationItem("Schedule", Icons.Filled.CalendarToday),
                    NavigationItem("Logs", Icons.Filled.List)
                )
                
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.name) },
                        label = { Text(item.name) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(application = this@MainActivity.application)
            }
            composable("user_config") {
                UserConfigScreen()
            }
            composable("admin_config") {
                AdminConfigScreen()
            }
            composable("schedule") {
                ScheduleScreen()
            }
            composable("logs") {
                LogsScreen()
            }
        }
    }
}

data class NavigationItem(val name: String, val icon: ImageVector) {
    val route: String = name.lowercase().replace(" ", "_")
}
