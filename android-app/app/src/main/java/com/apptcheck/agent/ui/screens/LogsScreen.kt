package com.apptcheck.agent.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.model.LogEntry
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider

/**
 * Logs Screen following TECHNICAL_SPEC.md section 7.6.
 * Scrollable log view with auto-scroll toggle, export and clear buttons.
 */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    var autoScroll by remember { mutableStateOf(true) }
    val logs = remember { mutableStateListOf<LogEntry>() }
    
    // Collect logs from LogManager
    LaunchedEffect(Unit) {
        LogManager.logFlow.collectLatest { entry ->
            logs.add(entry)
        }
    }
    
    // Load existing logs on startup
    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(LogManager.getAllLogs())
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Logs",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Row {
                // Auto-scroll toggle
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it }
                    )
                    Text("Auto-scroll")
                }
                
                // Export button
                IconButton(
                    onClick = {
                        // TODO: Implement export using share intent
                    }
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Share,
                        contentDescription = "Export"
                    )
                }
                
                // Clear button
                IconButton(
                    onClick = {
                        LogManager.clearInMemory()
                        logs.clear()
                    }
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Delete,
                        contentDescription = "Clear"
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Log display area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "No logs yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logs.size) { index ->
                        val entry = logs[index]
                        LogEntryItem(entry)
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    
                    // Auto-scroll to bottom
                    if (autoScroll && logs.isNotEmpty()) {
                        item {
                            LaunchedEffect(logs.size) {
                                // Scroll to bottom - would need LazyListState for proper implementation
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val timestampStr = remember(entry.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestamp))
    }
    
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                text = "[$timestampStr]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "[${entry.level}]",
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.level) {
                    "ERROR" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
