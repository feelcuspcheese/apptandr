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
 * Main Activity - entry point for the app.
 * Updated to handle Deep Doze requirements:
 * - Battery Optimization whitelisting prompt
 * - Exact Alarm permission check (Android 12+)
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

        LogManager.addLog("INFO", "App started")

        val configManager = ConfigManager.getInstance(applicationContext)
        lifecycleScope.launch {
            val config = configManager.configFlow.first()
            val activeSite = config.admin.activeSite
            LogManager.addLog("INFO", "Config loaded: activeSite=$activeSite")
        }

        // 1. Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. Exact Alarm Check (Android 12+)
        checkExactAlarmPermission()

        setContent {
            MaterialTheme {
                BookingBotApp()
            }
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                LogManager.addLog("WARN", "Exact Alarm permission missing - redirecting to settings")
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Prompt for battery optimization whitelisting if not already done
        promptBatteryOptimization()
    }

    private fun promptBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                LogManager.addLog("INFO", "Prompting user to disable battery optimization")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
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
