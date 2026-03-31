package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apptcheck.agent.service.BookingForegroundService

/**
 * Dashboard Screen following TECHNICAL_SPEC.md section 7.2.
 * Shows current run status, countdown, Start/Stop buttons, quick stats, and log preview.
 */
@Composable
fun DashboardScreen() {
    var status by remember { mutableStateOf("Idle") }
    val isRunning = BookingForegroundService.isRunning
    
    // Update status based on service state
    LaunchedEffect(isRunning) {
        status = if (isRunning) "Running" else "Idle"
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
                        onClick = { /* TODO: Implement start now */ },
                        enabled = !isRunning
                    ) {
                        Text("Start Now")
                    }
                    
                    Button(
                        onClick = { /* TODO: Implement stop */ },
                        enabled = isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
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
