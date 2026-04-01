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
import com.apptcheck.agent.model.ScheduleResult
import com.apptcheck.agent.viewmodel.ScheduleViewModel
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
    var selectedMuseumSlug by remember { mutableStateOf(uiState.selectedMuseum) }
    var selectedMode by remember { mutableStateOf(uiState.selectedMode) }
    var selectedDateTime by remember { mutableStateOf(uiState.selectedDateTime) }
    
    // Load museum name mapping for display (slug -> name)
    val configManager = remember { ConfigManager(viewModel.androidApplication.applicationContext) }
    var museumNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // slug -> name
    
    // Collect config flow to get museum names reactively
    LaunchedEffect(Unit) {
        try {
            configManager.configFlow.collect { config ->
                val siteKey = config.admin.activeSite
                val museums = config.admin.sites[siteKey]?.museums ?: emptyMap()
                museumNameMap = museums.values.associateBy({ it.slug }, { it.name })
            }
        } catch (e: Exception) {
            // Keep empty map on error
        }
    }
    
    // Update local state when UI state changes
    LaunchedEffect(uiState) {
        selectedSite = uiState.selectedSite
        selectedMuseumSlug = uiState.selectedMuseum
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
                sites.forEach { site: String ->
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
        
        // Museum Dropdown - displays friendly names, stores slugs
        var museumExpanded by remember { mutableStateOf(false) }
        val museumSlugs = uiState.availableMuseums  // These are slugs from ViewModel
        
        // Get display names for museums (slug -> name mapping)
        val museumDisplayNames = museumSlugs.mapNotNull { slug -> 
            museumNameMap[slug] 
        }.sorted()
        
        // Display value: show name if available, otherwise show slug
        val displayMuseumValue = selectedMuseumSlug.let { slug ->
            museumNameMap[slug] ?: slug.ifEmpty { "" }
        }
        
        ExposedDropdownMenuBox(
            expanded = museumExpanded,
            onExpandedChange = { museumExpanded = !museumExpanded }
        ) {
            OutlinedTextField(
                value = displayMuseumValue,
                onValueChange = {},
                readOnly = true,
                label = { Text("Museum") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                modifier = Modifier.fillMaxWidth(),
                enabled = museumSlugs.isNotEmpty()
            )
            ExposedDropdownMenu(
                expanded = museumExpanded,
                onDismissRequest = { museumExpanded = false }
            ) {
                if (museumDisplayNames.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No museums configured") },
                        onClick = { }
                    )
                } else {
                    museumDisplayNames.forEach { museumName: String ->
                        DropdownMenuItem(
                            text = { Text(museumName) },  // Display friendly name
                            onClick = {
                                // Find slug for this name
                                val slug = museumNameMap.entries.find { it.value == museumName }?.key ?: ""
                                selectedMuseumSlug = slug
                                museumExpanded = false
                                viewModel.onMuseumSelected(slug)
                            }
                        )
                    }
                }
            }
        }
        
        if (museumSlugs.isEmpty()) {
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
                modes.forEach { mode: String ->
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
            onValueChange = { newDateTime: String -> 
                selectedDateTime = newDateTime
                viewModel.onDateTimeSelected(newDateTime)
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
                if (selectedMuseumSlug.isNotEmpty() && selectedDateTime.isNotEmpty()) {
                    viewModel.scheduleRun(selectedSite, selectedMuseumSlug, selectedMode, selectedDateTime)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedMuseumSlug.isNotEmpty() && selectedDateTime.isNotEmpty()
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
            uiState.scheduledRuns.forEach { run: ScheduledRun ->
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
