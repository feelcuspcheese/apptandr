package com.booking.bot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
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
 * Sets up Jetpack Compose with BottomNavigation and NavHost.
 * Following TECHNICAL_SPEC.md section 5.
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

        // Initialize LogManager (done in BookingApplication.onCreate())
        // LogManager.init(applicationContext) - removed duplicate initialization
        LogManager.addLog("INFO", "App started")

        // Log configuration loaded (section 2 - Events Logged by the Android App)
        val configManager = ConfigManager.getInstance(applicationContext)
        lifecycleScope.launch {
            val config = configManager.configFlow.first()
            val activeSite = config.admin.activeSite
            LogManager.addLog("INFO", "Config loaded: activeSite=$activeSite")
        }

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
            MaterialTheme {
                BookingBotApp()
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
    
    // Wizard state - check if first-run wizard has been completed (WIZ-01 through WIZ-08)
    var showWizard by remember { mutableStateOf(false) }
    var wizardCheckComplete by remember { mutableStateOf(false) }
    
    // Check wizard status on launch
    LaunchedEffect(Unit) {
        val completed = configManager.isWizardCompleted()
        showWizard = !completed
        wizardCheckComplete = true
    }
    
    if (!wizardCheckComplete) {
        // Show loading while checking wizard status
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    
    // Show wizard if first run
    if (showWizard) {
        WizardScreen(
            configManager = configManager,
            onComplete = {
                showWizard = false
            },
            onCancel = {
                // Cancel should not save config or set flag (WIZ-08)
                showWizard = false
            }
        )
    } else {
        // Show main UI
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
                composable("dashboard") {
                    DashboardScreen(configManager = configManager)
                }
                composable("config") {
                    ConfigScreen(configManager = configManager)
                }
                composable("schedule") {
                    ScheduleScreen(configManager = configManager)
                }
                composable("logs") {
                    LogsScreen()
                }
            }
        }
    }
}
