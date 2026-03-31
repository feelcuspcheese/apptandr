package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.model.ScheduledRun
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Schedule Screen following TECHNICAL_SPEC.md section 7.5.
 * Schedule new runs and view/delete existing scheduled runs.
 * 
 * Features:
 * - Site dropdown shows both SPL and KCLS if configured in admin config
 * - Museum dropdown updates based on selected site and shows museums configured per site
 * - Visual feedback when schedule is confirmed and ready to trigger the agent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    
    var selectedSite by remember { mutableStateOf(uiState.selectedSite) }
    var selectedMuseum by remember { mutableStateOf(uiState.selectedMuseum) }
    var selectedMode by remember { mutableStateOf(uiState.selectedMode) }
    var selectedDateTime by remember { mutableStateOf(uiState.selectedDateTime) }
    
    // Update local state when UI state changes
    LaunchedEffect(uiState) {
        selectedSite = uiState.selectedSite
        selectedMuseum = uiState.selectedMuseum
        selectedMode = uiState.selectedMode
        selectedDateTime = uiState.selectedDateTime
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Schedule Run",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Site Dropdown - loads from admin config
        var siteExpanded by remember { mutableStateOf(false) }
        val sites = uiState.availableSites
        
        ExposedDropdownMenuBox(
            expanded = siteExpanded,
            onExpandedChange = { siteExpanded = !siteExpanded }
        ) {
            OutlinedTextField(
                value = selectedSite.uppercase(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Site") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteExpanded) },
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = siteExpanded,
                onDismissRequest = { siteExpanded = false }
            ) {
                sites.forEach { site ->
                    DropdownMenuItem(
                        text = { Text(site.uppercase()) },
                        onClick = {
                            selectedSite = site
                            selectedMuseum = "" // Reset museum when site changes
                            siteExpanded = false
                            viewModel.onSiteSelected(site)
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Museum Dropdown - loads from selected site's config
        var museumExpanded by remember { mutableStateOf(false) }
        val museums = uiState.availableMuseums
        
        ExposedDropdownMenuBox(
            expanded = museumExpanded,
            onExpandedChange = { museumExpanded = !museumExpanded }
        ) {
            OutlinedTextField(
                value = selectedMuseum,
                onValueChange = {},
                readOnly = true,
                label = { Text("Museum") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                modifier = Modifier.fillMaxWidth(),
                enabled = museums.isNotEmpty()
            )
            ExposedDropdownMenu(
                expanded = museumExpanded,
                onDismissRequest = { museumExpanded = false }
            ) {
                museums.forEach { museum ->
                    DropdownMenuItem(
                        text = { Text(museum) },
                        onClick = {
                            selectedMuseum = museum
                            museumExpanded = false
                            viewModel.onMuseumSelected(museum)
                        }
                    )
                }
            }
        }
        
        if (museums.isEmpty()) {
            Text(
                text = "No museums configured for this site. Please configure museums in Admin Config.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Mode Dropdown
        var modeExpanded by remember { mutableStateOf(false) }
        val modes = listOf("alert", "booking")
        
        ExposedDropdownMenuBox(
            expanded = modeExpanded,
            onExpandedChange = { modeExpanded = !modeExpanded }
        ) {
            OutlinedTextField(
                value = selectedMode,
                onValueChange = {},
                readOnly = true,
                label = { Text("Mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = modeExpanded,
                onDismissRequest = { modeExpanded = false }
            ) {
                modes.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode) },
                        onClick = {
                            selectedMode = mode
                            modeExpanded = false
                            viewModel.onModeSelected(mode)
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Date & Time Picker
        OutlinedTextField(
            value = selectedDateTime,
            onValueChange = { 
                selectedDateTime = it
                viewModel.onDateTimeSelected(it)
            },
            label = { Text("Date & Time (YYYY-MM-DD HH:MM)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("e.g., 2024-01-15 09:00") }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Schedule Button
        Button(
            onClick = {
                if (selectedMuseum.isNotEmpty() && selectedDateTime.isNotEmpty()) {
                    viewModel.scheduleRun(selectedSite, selectedMuseum, selectedMode, selectedDateTime)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedMuseum.isNotEmpty() && selectedDateTime.isNotEmpty()
        ) {
            Text("Schedule")
        }
        
        // Visual feedback for successful scheduling
        if (saveResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (saveResult!!.success) 
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
                        text = if (saveResult!!.success) 
                            "✓ Schedule confirmed! Agent will trigger at $selectedDateTime" 
                        else 
                            "✗ Failed to schedule: ${saveResult!!.error}",
                        color = if (saveResult!!.success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Scheduled Runs List
        Text(
            text = "Scheduled Runs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        if (uiState.scheduledRuns.isEmpty()) {
            Text(
                text = "No scheduled runs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            uiState.scheduledRuns.forEach { run ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("${run.siteKey}/${run.museumSlug}")
                            val dateTimeStr = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(run.dropTimeMillis),
                                ZoneId.systemDefault()
                            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            Text(dateTimeStr, style = MaterialTheme.typography.bodySmall)
                            Text(run.mode, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { viewModel.deleteScheduledRun(run.id) }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Delete,
                                contentDescription = "Delete"
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ScheduleResult(
    val success: Boolean,
    val error: String? = null
)
