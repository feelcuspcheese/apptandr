
package com.booking.bot.ui.screens

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booking.bot.data.*
import com.booking.bot.scheduler.AlarmScheduler
import com.booking.bot.service.BookingForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

/**
 * DashboardScreen following TECHNICAL_SPEC.md section 5.1.
 * 
 * v1.1 Enhancements:
 * - Leak-proof "Start Now" snapshotting.
 * - 30-second countdown with progress feedback.
 * v1.3 Enhancements:
 * - History Section (Feature 1)
 * - Master Switch (Feature 3)
 * - Live Status Phase Pulse (Feature 4)
 * - Credential Snapshotting (Fix): Locks default card at trigger moment.
 * v1.5 Bug Fixes:
 * - Friendly Calendar Title (Museum Name vs ID).
 * - Fixed Calendar Icon click logic (Intent Flag update).
 * v1.6 Fix (Android 16 Compatibility):
 * - Robust Calendar Intent resolution using CORRECT dir/event MIME type to bypass permission limits.
 */
@Composable
fun DashboardScreen(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by configManager.configFlow.collectAsState(initial = null)
    val isRunning by BookingForegroundService.isRunning.collectAsState()
    val goStatus by BookingForegroundService.goStatus.collectAsState()
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

    // v1.3 Pulse Animation for Active Strike (Feature 4)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "System Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        // Feature 3: Master Switch UI
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (config?.general?.isPaused == true) "PAUSED" else "ENABLED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (config?.general?.isPaused == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = config?.general?.isPaused != true,
                                onCheckedChange = { scope.launch { configManager.setPaused(!it) } }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val statusText = if (config?.general?.isPaused == true) "Paused" 
                                     else if (isRunning) goStatus 
                                     else if (isStarting) "Initializing..." 
                                     else "Idle"
                    
                    val statusColor = if (config?.general?.isPaused == true) MaterialTheme.colorScheme.error
                                      else if (isRunning) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurface

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.alpha(if (isRunning) pulseAlpha else 1.0f) // Feature 4 pulse
                    )

                    Spacer(modifier = Modifier.height(24.dp))

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

                                        // CREDENTIAL SNAPSHOTTING (v1.3 Fix): 
                                        // Resolve the default ID now so it's locked into the run object.
                                        val site = currentConfig.admin.sites[siteKey]
                                        val resolvedCredentialId = site?.defaultCredentialId

                                        val dropTimeMillis = System.currentTimeMillis() + 1000 
                                        val timezone = java.util.TimeZone.getDefault().id

                                        // LEAK-PROOF FIX: Explicitly snapshot global preferences into the run object
                                        val run = ScheduledRun(
                                            id = UUID.randomUUID().toString(),
                                            siteKey = siteKey,
                                            museumSlug = museumSlug,
                                            credentialId = resolvedCredentialId,
                                            dropTimeMillis = dropTimeMillis,
                                            mode = currentConfig.general.mode,
                                            preferredDays = currentConfig.general.preferredDays,
                                            preferredDates = currentConfig.general.preferredDates,
                                            timezone = timezone,
                                            isRecurring = false
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
                            enabled = !isStarting && !isRunning && config?.general?.isPaused != true
                        ) {
                            if (isStarting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${countdown}s")
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
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Next Scheduled Run",
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
                                Text(
                                    text = "In ${minutes}m ${seconds}s",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
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
                            Text("No scheduled runs pending")
                        }
                    } ?: Text("Loading...")
                }
            }
        }

        // v1.3 Feature 1: Recent Activity History Section
        item {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
        }

        val history = config?.runHistory ?: emptyList()
        if (history.isEmpty()) {
            item {
                Text(
                    "No previous runs recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(history) { result ->
                HistoryItem(result)
            }
            item {
                TextButton(onClick = { scope.launch { configManager.clearHistory() } }) {
                    Text("Clear Activity Log")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Quick Settings Overview",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    config?.let { cfg ->
                        val activeSite = cfg.admin.sites[cfg.admin.activeSite]
                        val preferredMuseum = activeSite?.museums?.get(cfg.general.preferredMuseumSlug)

                        Text("Active Site: ${cfg.admin.activeSite.uppercase()}")
                        Text("Default Mode: ${cfg.general.mode.replaceFirstChar { it.uppercase() }}")
                        Text("Default Museum: ${preferredMuseum?.name ?: "None"}")
                    } ?: Text("Loading...")
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(result: RunResult) {
    val context = LocalContext.current
    val timeStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(result.timestamp))
    val statusColor = when (result.status) {
        "SUCCESS" -> Color(0xFF4CAF50)
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (result.status) {
                    "SUCCESS" -> Icons.Default.CheckCircle
                    "FAILED" -> Icons.Default.Error
                    else -> Icons.Default.History
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${result.museumName} (${result.siteName})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${result.status} • $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // v1.6 Fix: Strict MIME Type declaration for Android 16 Intent Resolution
            if (result.status == "SUCCESS") {
                IconButton(onClick = {
                    try {
                        val regex = "\\d{4}-\\d{2}-\\d{2}".toRegex()
                        val match = regex.find(result.message)?.value
                        
                        if (match != null) {
                            val date = LocalDate.parse(match)
                            val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            
                            // CORRECT MIME TYPE: vnd.android.cursor.dir/event (Directory insertion bypasses READ_CALENDAR limits)
                            val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
                                putExtra(CalendarContract.Events.TITLE, "${result.museumName} Visit : Pass confirmed")
                                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dateMillis + (9 * 60 * 60 * 1000)) // Starts at 9 AM
                                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dateMillis + (17 * 60 * 60 * 1000))   // Ends at 5 PM
                                putExtra(CalendarContract.Events.ALL_DAY, true)
                                putExtra(CalendarContract.Events.DESCRIPTION, "Auto-booked by Booking Bot.\n${result.message}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(calIntent)
                        } else {
                            LogManager.addLog("WARN", "Calendar parse failed: Could not find a valid date in message: ${result.message}")
                        }
                    } catch (e: Exception) {
                        // FIX: Explicitly log intent resolution failures to the LogsScreen instead of failing silently
                        LogManager.addLog("ERROR", "Failed to launch Calendar App: ${e.message}")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = "Add to calendar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
