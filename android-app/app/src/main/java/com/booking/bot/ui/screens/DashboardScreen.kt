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
import java.util.*

/**
 * DashboardScreen following TECHNICAL_SPEC.md section 5.1.
 * 
 * v1.1 Enhancements:
 * - Leak-proof "Start Now" snapshotting.
 * - 30-second countdown with progress feedback.
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

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    // Start Now countdown state (DB-10)
    var isStarting by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    val countdownJobState = remember { mutableStateOf<Job?>(null) }

    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            isStarting = false
            countdown = 0
            countdownJobState.value?.cancel()
        }
        actionFeedback = null
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            countdownJobState.value?.cancel()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    text = if (isRunning) "Running" else if (isStarting) "Starting..." else "Idle",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (isRunning || isStarting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            isStarting = true
                            countdown = 30
                            
                            countdownJobState.value = scope.launch {
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

                                    // Countdown loop (DB-10)
                                    while (countdown > 0) {
                                        delay(1000)
                                        if (isStarting) countdown-- else break
                                    }

                                    if (!isStarting) return@launch

                                    val credentialId = currentConfig.admin.sites[siteKey]?.defaultCredentialId
                                    val dropTimeMillis = System.currentTimeMillis() + 1000 
                                    val timezone = java.util.TimeZone.getDefault().id

                                    // LEAK-PROOF FIX: Explicitly snapshot global preferences into the run object
                                    val run = ScheduledRun(
                                        id = UUID.randomUUID().toString(),
                                        siteKey = siteKey,
                                        museumSlug = museumSlug,
                                        credentialId = credentialId,
                                        dropTimeMillis = dropTimeMillis,
                                        mode = currentConfig.general.mode,
                                        preferredDays = currentConfig.general.preferredDays,
                                        preferredDates = currentConfig.general.preferredDates,
                                        timezone = timezone,
                                        isRecurring = false // Start Now is always one-time
                                    )

                                    LogManager.addLog("INFO", "Start Now run created: id=${run.id}, museum=$museumSlug")

                                    configManager.addScheduledRun(run)
                                    val offsetMillis = AlarmScheduler.parseDurationToMillis(currentConfig.general.preWarmOffset)
                                    
                                    AlarmScheduler(context).scheduleRun(run, offsetMillis)

                                    actionFeedback = "Agent scheduled to start."
                                    actionSuccess = true
                                } catch (e: Exception) {
                                    actionFeedback = "Failed to schedule: ${e.message}"
                                    actionSuccess = false
                                } finally {
                                    isStarting = false
                                }
                            }
                        },
                        enabled = !isStarting && !isRunning
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                                    if (isStarting) {
                                        isStarting = false
                                        countdownJobState.value?.cancel()
                                        actionFeedback = "Start cancelled."
                                    } else {
                                        BookingForegroundService.stop(context)
                                        actionFeedback = "Stopping agent..."
                                    }
                                    actionSuccess = true
                                } catch (e: Exception) {
                                    actionFeedback = "Failed: ${e.message}"
                                    actionSuccess = false
                                }
                            }
                        },
                        enabled = isRunning || isStarting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
                    }
                }

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
                        .filter { it.dropTimeMillis > currentTime }
                        .minByOrNull { it.dropTimeMillis }

                    if (nextRun != null) {
                        val timeUntil = nextRun.dropTimeMillis - currentTime
                        if (timeUntil > 0) {
                            val minutes = timeUntil / 60000
                            val seconds = (timeUntil % 60000) / 1000
                            Text("In ${minutes}m ${seconds}s")
                        } else {
                            Text("Running or pending...")
                        }

                        val site = cfg.admin.sites[nextRun.siteKey]
                        val museum = site?.museums?.get(nextRun.museumSlug)
                        Text(
                            text = "${museum?.name ?: nextRun.museumSlug} • ${nextRun.mode.uppercase()}${if (nextRun.isRecurring) " (Recurring)" else ""}",
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
