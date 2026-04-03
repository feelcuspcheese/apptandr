package com.booking.bot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.booking.bot.data.ConfigManager
import com.booking.bot.data.LogManager
import com.booking.bot.data.ScheduledRun
import com.booking.bot.scheduler.AlarmScheduler
import com.booking.bot.service.BookingForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * DashboardScreen following TECHNICAL_SPEC.md section 5.1.
 *
 * Features:
 * - Status Card showing "Running" or "Idle"
 * - Next Run Countdown from sorted scheduledRuns
 * - Start Now Button (creates run with +30s delay) with countdown timer (DB-10)
 * - Stop Button (calls BookingForegroundService.stop())
 * - Quick Stats: Active Site, Mode, Preferred Museum (by name)
 */
@Composable
fun DashboardScreen(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by configManager.configFlow.collectAsState(initial = null)
    val isRunning by BookingForegroundService.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    // Start Now countdown state (DB-10)
    var isStarting by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var countdownJob: Job? = remember { null }

    // Action feedback state
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf(false) }

    // Clear feedback when running state changes
    LaunchedEffect(isRunning) {
        actionFeedback = null
    }

    // Cleanup countdown job on dispose
    DisposableEffect(Unit) {
        onDispose {
            countdownJob?.cancel()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Current Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRunning) "Running" else "Idle",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Start/Stop Buttons (section 5.1, DB-10)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            // Start countdown and then start the agent (DB-10)
                            isStarting = true
                            countdown = 30
                            
                            countdownJob = scope.launch {
                                // Countdown loop
                                while (countdown > 0) {
                                    delay(1000)
                                    countdown--
                                }
                                
                                // Actually start the agent after countdown
                                try {
                                    val currentConfig = config ?: return@launch
                                    val siteKey = currentConfig.admin.activeSite
                                    val museumSlug = currentConfig.general.preferredMuseumSlug.ifEmpty {
                                        currentConfig.admin.sites[siteKey]?.museums?.keys?.firstOrNull() ?: ""
                                    }

                                    if (museumSlug.isEmpty()) {
                                        actionFeedback = "No museum configured. Please configure in Admin Config."
                                        actionSuccess = false
                                        isStarting = false
                                        return@launch
                                    }

                                    val credentialId = currentConfig.admin.sites[siteKey]?.defaultCredentialId
                                    val dropTimeMillis = System.currentTimeMillis() + 30_000 // 30 seconds
                                    val timezone = java.util.TimeZone.getDefault().id

                                    val run = ScheduledRun(
                                        id = UUID.randomUUID().toString(),
                                        siteKey = siteKey,
                                        museumSlug = museumSlug,
                                        credentialId = credentialId,
                                        dropTimeMillis = dropTimeMillis,
                                        mode = currentConfig.general.mode,
                                        timezone = timezone
                                    )

                                    // [7.4.4]: Log Start Now run created
                                    LogManager.addLog("INFO", "Start Now run created: id=${run.id}, dropTime in 30 seconds")

                                    configManager.addScheduledRun(run)
                                    AlarmScheduler(context).scheduleRun(run)

                                    // [FIX (BUG-005)]: Immediately start the foreground service so that logs appear instantly
                                    BookingForegroundService.start(context, run)

                                    actionFeedback = "Agent started successfully"
                                    actionSuccess = true
                                } catch (e: Exception) {
                                    actionFeedback = "Failed to start: ${e.message}"
                                    actionSuccess = false
                                } finally {
                                    isStarting = false
                                }
                            }
                        },
                        enabled = !isStarting && !isRunning
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Starting in ${countdown}s")
                        } else {
                            Text("Start Now")
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    BookingForegroundService.stop(context)
                                    actionFeedback = "Stopping agent..."
                                    actionSuccess = true
                                    // Cancel countdown if stopping
                                    countdownJob?.cancel()
                                    isStarting = false
                                } catch (e: Exception) {
                                    actionFeedback = "Failed to stop: ${e.message}"
                                    actionSuccess = false
                                }
                            }
                        },
                        enabled = isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
                    }
                }

                // Action feedback
                if (actionFeedback != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (actionSuccess)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                actionFeedback!!,
                                color = if (actionSuccess)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Next Run Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Next Run",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                config?.let { cfg ->
                    val nextRun = cfg.scheduledRuns
                        .filter { it.dropTimeMillis > System.currentTimeMillis() }
                        .minByOrNull { it.dropTimeMillis }

                    if (nextRun != null) {
                        val timeUntil = nextRun.dropTimeMillis - System.currentTimeMillis()
                        val minutes = timeUntil / 60000
                        val seconds = (timeUntil % 60000) / 1000
                        Text("In ${minutes}m ${seconds}s")

                        val site = cfg.admin.sites[nextRun.siteKey]
                        val museum = site?.museums?.get(nextRun.museumSlug)
                        Text(
                            text = "${museum?.name ?: nextRun.museumSlug} • ${nextRun.mode.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("No scheduled runs")
                    }
                } ?: Text("Loading...")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Stats Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Quick Stats",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                config?.let { cfg ->
                    val activeSite = cfg.admin.sites[cfg.admin.activeSite]
                    val preferredMuseum = activeSite?.museums?.get(cfg.general.preferredMuseumSlug)

                    Text("Active Site: ${cfg.admin.activeSite.uppercase()}")
                    Text("Mode: ${cfg.general.mode.replaceFirstChar { it.uppercase() }}")
                    Text("Preferred Museum: ${preferredMuseum?.name ?: "None"}")
                } ?: Text("Loading...")
            }
        }
    }
}
