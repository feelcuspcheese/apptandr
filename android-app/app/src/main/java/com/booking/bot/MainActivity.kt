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

/**
 * Main Activity - Entry point for the Booking Bot application.
 * 
 * Audit Fixes:
 * 1. Implements a safe, non-recursive permission gauntlet.
 * 2. Handles Notification -> Exact Alarm -> Battery Optimization sequentially.
 * 3. Prevents ActivityNotFoundException on restricted OEM devices.
 */
class MainActivity : ComponentActivity() {

    // Launcher for Notification Permission (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            LogManager.addLog("INFO", "Notification permission granted")
        } else {
            LogManager.addLog("WARN", "Notification permission denied")
        }
        // Proceed to next step in gauntlet
        checkExactAlarmPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.addLog("INFO", "App started")

        // Initialize Config and log current state
        val configManager = ConfigManager.getInstance(applicationContext)
        lifecycleScope.launch {
            val config = configManager.configFlow.first()
            LogManager.addLog("INFO", "Config loaded: activeSite=${config.admin.activeSite}")
        }

        // Start sequential permission checks (Gauntlet)
        initPermissionGauntlet()

        setContent {
            MaterialTheme {
                BookingBotApp()
            }
        }
    }

    /**
     * Step 1: Notifications
     */
    private fun initPermissionGauntlet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (status != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkExactAlarmPermission()
            }
        } else {
            checkExactAlarmPermission()
        }
    }

    /**
     * Step 2: Exact Alarms (Android 12+)
     */
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                LogManager.addLog("WARN", "Exact Alarm permission missing")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback for some device variants
                    try {
                        startActivity(Intent(Settings.ACTION_ALARM_READY_LIST))
                    } catch (e2: Exception) {
                        LogManager.addLog("ERROR", "Could not open Exact Alarm settings")
                    }
                }
            }
        }
        // Proceed to next step
        checkBatteryOptimizations()
    }

    /**
     * Step 3: Battery Optimization (Deep Doze Protection)
     */
    private fun checkBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                LogManager.addLog("INFO", "Requesting Battery Optimization whitelist")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        // Fallback to the general list if direct request is blocked by OEM
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Please disable battery optimization for this app manually", Toast.LENGTH_LONG).show()
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
        val completed = configManager.isWizardCompleted()
        showWizard = !completed
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
                composable("dashboard") { DashboardScreen(configManager = configManager) }
                composable("config") { ConfigScreen(configManager = configManager) }
                composable("schedule") { ScheduleScreen(configManager = configManager) }
                composable("logs") { LogsScreen() }
            }
        }
    }
}
