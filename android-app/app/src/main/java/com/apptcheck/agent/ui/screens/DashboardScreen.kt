package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apptcheck.agent.service.BookingForegroundService
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.data.ConfigManager
import kotlinx.coroutines.launch
import android.content.Intent
import android.app.Application

/**
 * Dashboard Screen following TECHNICAL_SPEC.md section 7.2.
 * Shows current run status, countdown, Start/Stop buttons, quick stats, and log preview.
 * 
 * Features:
 * - Start Now button creates an immediate run (in 30 seconds) with visual feedback
 * - Stop button cancels the current run with visual feedback
 * - Live status updates from the service
 */
@Composable
fun DashboardScreen(application: Application = androidx.lifecycle.viewmodel.compose.viewModel<Application>()) {
    var status by remember { mutableStateOf("Idle") }
    val isRunning = BookingForegroundService.isRunning
    
    // For start/stop feedback
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(application) }
    
    // Update status based on service state
    LaunchedEffect(isRunning) {
        status = if (isRunning) "Running" else "Idle"
        // Clear feedback when status changes
        actionFeedback = null
    }
    
    Column(
        modifier = Modifier
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
                    text = status,
                    style = MaterialTheme.typography.headlineLarge,
                    color = when (status) {
                        "Running" -> MaterialTheme.colorScheme.primary
                        "Waiting" -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Start/Stop Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    // Create a run for 30 seconds from now
                                    val runId = java.util.UUID.randomUUID().toString()
                                    val config = configManager.loadConfig()
                                    val siteKey = config.admin.activeSite
                                    val museumSlug = config.user.preferredSlug.ifEmpty { 
                                        config.admin.sites[siteKey]?.museums?.keys?.firstOrNull() ?: "" 
                                    }
                                    
                                    if (museumSlug.isEmpty()) {
                                        actionFeedback = "No museum configured. Please configure in Admin Config."
                                        actionSuccess = false
                                        return@launch
                                    }
                                    
                                    val dropTimeMillis = System.currentTimeMillis() + 30000 // 30 seconds
                                    
                                    val intent = Intent(application, com.apptcheck.agent.scheduler.AlarmReceiver::class.java).apply {
                                        putExtra("run_id", runId)
                                        putExtra("site_key", siteKey)
                                        putExtra("museum_slug", museumSlug)
                                        putExtra("drop_time", dropTimeMillis)
                                        putExtra("mode", config.user.mode)
                                        putExtra("start_now", true) // Flag for immediate start
                                    }
                                    
                                    application.sendBroadcast(intent)
                                    
                                    actionFeedback = "Starting agent in 30 seconds..."
                                    actionSuccess = true
                                } catch (e: Exception) {
                                    actionFeedback = "Failed to start: ${e.message}"
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
                                    // Send stop broadcast
                                    val intent = Intent(application, com.apptcheck.agent.service.BookingForegroundService::class.java).apply {
                                        action = "com.apptcheck.agent.STOP_AGENT"
                                    }
                                    application.startService(intent)
                                    
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
                // TODO: Load from config properly with StateFlow
                Text("Active Site: SPL") // TODO: Load from config
                Text("Mode: Alert") // TODO: Load from config
                Text("Preferred Museum: Seattle Art Museum") // TODO: Load from config
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Log Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Recent Logs",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No recent logs", // TODO: Load from LogManager
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
