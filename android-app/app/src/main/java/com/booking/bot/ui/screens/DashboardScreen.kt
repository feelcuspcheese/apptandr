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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

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

    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        actionFeedback = null
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
                    text = if (isRunning) "Running" else "Idle",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val currentConfig = config ?: return@launch
                                    val siteKey = currentConfig.admin.activeSite
                                    val museumSlug = currentConfig.general.preferredMuseumSlug.ifEmpty {
                                        currentConfig.admin.sites[siteKey]?.museums?.keys?.firstOrNull() ?: ""
                                    }

                                    if (museumSlug.isEmpty()) {
                                        actionFeedback = "No museum configured. Please configure in Admin Config."
                                        actionSuccess = false
                                        return@launch
                                    }

                                    val credentialId = currentConfig.admin.sites[siteKey]?.defaultCredentialId
                                    val dropTimeMillis = System.currentTimeMillis() + 30_000
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

                                    LogManager.addLog("INFO", "Start Now run created: id=${run.id}, dropTime in 30 seconds")

                                    // Add the run to DataStore; the Next Run tile will automatically pick it up
                                    configManager.addScheduledRun(run)
                                    // Let AlarmScheduler handle starting the service exactly as it does for regular schedules
                                    AlarmScheduler(context).scheduleRun(run)

                                    actionFeedback = "Agent scheduled to start in 30s"
                                    actionSuccess = true
                                } catch (e: Exception) {
                                    actionFeedback = "Failed to schedule: ${e.message}"
                                    actionSuccess = false
                                }
                            }
                        },
                        enabled = !isRunning
                    ) {
                        Text("Start Now")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    BookingForegroundService.stop(context)
                                    actionFeedback = "Stopping agent..."
                                    actionSuccess = true
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
