package com.booking.bot

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.ui.navigation.BottomNavItem
import com.booking.bot.ui.screens.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Permission Launcher for Notifications (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            LogManager.addLog("INFO", "Notification permission granted")
        } else {
            LogManager.addLog("WARN", "Notification permission denied")
        }
        // Move to the next check in the gauntlet
        checkExactAlarmPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.addLog("INFO", "App started")

        // Start the permission gauntlet
        startPermissionGauntlet()

        setContent {
            MaterialTheme {
                BookingBotApp()
            }
        }
    }

    /**
     * Sequence: 
     * 1. Notifications -> 
     * 2. Exact Alarms -> 
     * 3. Battery Optimizations
     */
    private fun startPermissionGauntlet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkExactAlarmPermission()
            }
        } else {
            checkExactAlarmPermission()
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                LogManager.addLog("WARN", "Exact Alarm permission missing - redirecting")
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } catch (e: Exception) {
                    // Fallback to general settings if specific action fails
                    startActivity(Intent(Settings.ACTION_ALARM_READY_LIST))
                }
            }
        }
        // Final step: Battery check
        promptBatteryOptimization()
    }

    private fun promptBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                LogManager.addLog("INFO", "Prompting for Battery Optimization whitelist")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to the settings list if the direct prompt fails
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Please disable battery optimization for this app in settings.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@Composable
fun BookingBotApp() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val configManager = remember { ConfigManager.getInstance(context) }
    
    var showWizard by remember { mutableStateOf(false) }
    var wizardCheckComplete by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        showWizard = !configManager.isWizardCompleted()
        wizardCheckComplete = true
    }
    
    if (!wizardCheckComplete) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    
    if (showWizard) {
        WizardScreen(
            configManager = configManager,
            onComplete = { showWizard = false },
            onCancel = { showWizard = false }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    BottomNavItem.items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) },
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(navController, "dashboard", Modifier.padding(innerPadding)) {
                composable("dashboard") { DashboardScreen(configManager) }
                composable("config") { ConfigScreen(configManager) }
                composable("schedule") { ScheduleScreen(configManager) }
                composable("logs") { LogsScreen() }
            }
        }
    }
}
