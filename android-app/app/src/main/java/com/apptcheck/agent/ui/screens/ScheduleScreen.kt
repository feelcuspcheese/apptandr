package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.Delete

/**
 * Schedule Screen following TECHNICAL_SPEC.md section 7.5.
 * Schedule new runs and view/delete existing scheduled runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen() {
    var selectedSite by remember { mutableStateOf("spl") }
    var selectedMuseum by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("alert") }
    var selectedDateTime by remember { mutableStateOf("") }
    
    // Sample scheduled runs (TODO: Load from ConfigManager)
    val scheduledRuns = remember { mutableStateListOf<ScheduledRunItem>() }
    
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
        
        // Site Dropdown
        var siteExpanded by remember { mutableStateOf(false) }
        val sites = listOf("spl", "kcls")
        
        ExposedDropdownMenuBox(
            expanded = siteExpanded,
            onExpandedChange = { siteExpanded = !siteExpanded }
        ) {
            OutlinedTextField(
                value = selectedSite,
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
                        text = { Text(site) },
                        onClick = {
                            selectedSite = site
                            siteExpanded = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Museum Dropdown
        var museumExpanded by remember { mutableStateOf(false) }
        val museums = when (selectedSite) {
            "spl" -> listOf("seattle-art-museum", "zoo")
            "kcls" -> listOf("kidsquest")
            else -> emptyList()
        }
        
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
                modifier = Modifier.fillMaxWidth()
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
                        }
                    )
                }
            }
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
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Date & Time Picker
        OutlinedTextField(
            value = selectedDateTime,
            onValueChange = { selectedDateTime = it },
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
                    // TODO: Parse datetime and create ScheduledRun
                    scheduledRuns.add(
                        ScheduledRunItem(selectedSite, selectedMuseum, selectedMode, selectedDateTime)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Schedule")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Scheduled Runs List
        Text(
            text = "Scheduled Runs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        if (scheduledRuns.isEmpty()) {
            Text(
                text = "No scheduled runs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            scheduledRuns.forEachIndexed { index, run ->
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
                            Text("${run.site}/${run.museum}")
                            Text(run.dateTime, style = MaterialTheme.typography.bodySmall)
                            Text(run.mode, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scheduledRuns.removeAt(index) }) {
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

data class ScheduledRunItem(
    val site: String,
    val museum: String,
    val mode: String,
    val dateTime: String
)
