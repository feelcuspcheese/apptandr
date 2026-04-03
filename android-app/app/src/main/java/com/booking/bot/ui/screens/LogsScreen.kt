package com.booking.bot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.booking.bot.data.LogEntry
import com.booking.bot.data.LogManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * LogsScreen following TECHNICAL_SPEC.md section 5.4.
 * Displays live logs with auto-scroll, export, and clear functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe logs reactively from LogManager.logFlow (section 5.4)
    val logs = remember { mutableStateListOf<LogEntry>() }

    // Load existing logs on first composition (section 5.4 - LOG-21)
    LaunchedEffect(Unit) {
        logs.addAll(LogManager.getCurrentLogs())
    }

    // Collect new logs via logFlow (section 5.4)
    LaunchedEffect(Unit) {
        LogManager.logFlow.collect { entry ->
            logs.add(entry)
            // Keep buffer size manageable
            if (logs.size > 500) {
                logs.removeAt(0)
            }
        }
    }

    // Auto-scroll state using LazyListState
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Scroll to bottom when new logs arrive and auto-scroll is enabled
    LaunchedEffect(logs.size, autoScrollEnabled) {
        if (autoScrollEnabled && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    // Feedback state
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Live Logs", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Auto-scroll toggle
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = autoScrollEnabled,
                        onCheckedChange = { autoScrollEnabled = it }
                    )
                    Text("Auto-scroll", style = MaterialTheme.typography.bodySmall)
                }

                // Export button
                IconButton(
                    onClick = {
                        scope.launch {
                            try {
                                val uri = LogManager.exportLogs(context)
                                // Share intent with chooser
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share logs via"))
                                feedbackMessage = "Logs exported successfully"
                            } catch (e: Exception) {
                                feedbackMessage = "Export failed: ${e.message}"
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Export")
                }

                // Clear button
                IconButton(
                    onClick = {
                        LogManager.clearInMemory()
                        feedbackMessage = "Logs cleared"
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
                }
            }
        }

        // Feedback message
        if (feedbackMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    feedbackMessage!!,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Logs list
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("No logs yet", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    state = listState
                ) {
                    items(logs) { log ->
                        LogItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: com.booking.bot.data.LogEntry) {
    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))

    val levelColor = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        "INFO" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "[${log.level}]",
                style = MaterialTheme.typography.bodySmall,
                color = levelColor,
                modifier = Modifier.width(50.dp)
            )
            Text(
                log.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
