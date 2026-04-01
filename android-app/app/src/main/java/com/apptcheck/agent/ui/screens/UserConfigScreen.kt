package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import com.apptcheck.agent.model.Defaults
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apptcheck.agent.viewmodel.UserConfigViewModel
import com.apptcheck.agent.data.ConfigManager
import kotlinx.coroutines.launch

/**
 * User Config Screen following TECHNICAL_SPEC.md section 7.3.
 * All user-editable fields with Save button.
 * Integrated with UserConfigViewModel for persistent state across navigation.
 * 
 * Bug Fix #1: Preferred Museum Slug is now a dropdown populated from admin config museums
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserConfigScreen(viewModel: UserConfigViewModel = viewModel()) {
    // Observe config from ViewModel
    val userConfig by viewModel.userConfig.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    
    // Load admin config to get museums for dropdown (with names)
    val context = LocalContext.current.applicationContext
    val configManager = remember { ConfigManager(context) }
    var availableMuseumNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var museumSlugMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // name -> slug mapping
    
    // Load museums when screen is composed - reactive to config changes
    LaunchedEffect(Unit) {
        try {
            configManager.configFlow.collect { config ->
                val siteKey = config.admin.activeSite
                val museums = config.admin.sites[siteKey]?.museums ?: emptyMap()
                // Create list of museum names for display
                availableMuseumNames = museums.values.map { it.name }.sorted()
                // Create reverse mapping from name to slug for storage
                museumSlugMap = museums.values.associateBy({ it.name }, { it.slug })
            }
        } catch (e: Exception) {
            // Keep empty list on error
        }
    }
    
    // Local state variables bound to ViewModel state
    var mode by remember { mutableStateOf(userConfig.mode) }
    var strikeTime by remember { mutableStateOf(userConfig.strikeTime) }
    var ntfyTopic by remember { mutableStateOf(userConfig.ntfyTopic) }
    var preferredSlug by remember { mutableStateOf(userConfig.preferredSlug) }
    
    // Performance tuning fields
    var checkWindow by remember { mutableStateOf(userConfig.checkWindow) }
    var checkInterval by remember { mutableStateOf(userConfig.checkInterval) }
    var requestJitter by remember { mutableStateOf(userConfig.requestJitter) }
    var monthsToCheck by remember { mutableStateOf(userConfig.monthsToCheck.toString()) }
    var preWarmOffset by remember { mutableStateOf(userConfig.preWarmOffset) }
    var maxWorkers by remember { mutableStateOf(userConfig.maxWorkers.toString()) }
    var restCycleChecks by remember { mutableStateOf(userConfig.restCycleChecks.toString()) }
    var restCycleDuration by remember { mutableStateOf(userConfig.restCycleDuration) }
    
    // Preferred days
    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    var selectedDays by remember { mutableStateOf(userConfig.preferredDays.toSet()) }
    
    // Update local state when ViewModel state changes
    LaunchedEffect(userConfig) {
        mode = userConfig.mode
        strikeTime = userConfig.strikeTime
        ntfyTopic = userConfig.ntfyTopic
        preferredSlug = userConfig.preferredSlug
        checkWindow = userConfig.checkWindow
        checkInterval = userConfig.checkInterval
        requestJitter = userConfig.requestJitter
        monthsToCheck = userConfig.monthsToCheck.toString()
        preWarmOffset = userConfig.preWarmOffset
        maxWorkers = userConfig.maxWorkers.toString()
        restCycleChecks = userConfig.restCycleChecks.toString()
        restCycleDuration = userConfig.restCycleDuration
        selectedDays = userConfig.preferredDays.toSet()
    }
    
    // Performance section expanded state
    var performanceExpanded by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "User Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Mode selection
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mode", style = MaterialTheme.typography.titleMedium)
                Row {
                    RadioButton(
                        selected = mode == "alert",
                        onClick = { mode = "alert" }
                    )
                    Text("Alert", modifier = Modifier.padding(start = 8.dp))
                    
                    RadioButton(
                        selected = mode == "booking",
                        onClick = { mode = "booking" }
                    )
                    Text("Booking", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Strike Time
        OutlinedTextField(
            value = strikeTime,
            onValueChange = { strikeTime = it },
            label = { Text("Strike Time (HH:MM)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Preferred Days
        Text("Preferred Days", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allDays.forEach { day ->
                FilterChip(
                    selected = day in selectedDays,
                    onClick = {
                        selectedDays = if (day in selectedDays) {
                            selectedDays - day
                        } else {
                            selectedDays + day
                        }
                    },
                    label = { Text(day) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Ntfy Topic
        OutlinedTextField(
            value = ntfyTopic,
            onValueChange = { ntfyTopic = it },
            label = { Text("Ntfy Topic") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Preferred Museum - DROPDOWN WITH FRIENDLY NAMES
        var museumExpanded by remember { mutableStateOf(false) }
        
        // Display name for the selected museum (show friendly name, store slug)
        val displayMuseumName = remember(preferredSlug, museumSlugMap) {
            museumSlugMap.entries.find { it.value == preferredSlug }?.key 
                ?: preferredSlug.ifEmpty { "" }
        }
        
        ExposedDropdownMenuBox(
            expanded = museumExpanded,
            onExpandedChange = { museumExpanded = !museumExpanded }
        ) {
            OutlinedTextField(
                value = displayMuseumName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Preferred Museum") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                modifier = Modifier.fillMaxWidth(),
                enabled = availableMuseumNames.isNotEmpty()
            )
            ExposedDropdownMenu(
                expanded = museumExpanded,
                onDismissRequest = { museumExpanded = false }
            ) {
                if (availableMuseumNames.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No museums configured") },
                        onClick = { }
                    )
                } else {
                    availableMuseumNames.forEach { museumName: String ->
                        DropdownMenuItem(
                            text = { Text(museumName) },  // Display friendly name
                            onClick = {
                                preferredSlug = museumSlugMap[museumName] ?: ""  // Store slug
                                museumExpanded = false
                            }
                        )
                    }
                }
            }
        }
        
        if (availableMuseumNames.isEmpty()) {
            Text(
                text = "No museums configured. Please configure museums in Admin Config.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Performance Tuning Section
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Performance Tuning", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { performanceExpanded = !performanceExpanded }) {
                        Icon(
                            imageVector = if (performanceExpanded) 
                                androidx.compose.material.icons.Icons.Filled.ExpandLess 
                            else 
                                androidx.compose.material.icons.Icons.Filled.ExpandMore,
                            contentDescription = "Toggle"
                        )
                    }
                }
                
                if (performanceExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkWindow,
                        onValueChange = { checkWindow = it },
                        label = { Text("Check Window") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkInterval,
                        onValueChange = { checkInterval = it },
                        label = { Text("Check Interval") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = requestJitter,
                        onValueChange = { requestJitter = it },
                        label = { Text("Request Jitter") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = monthsToCheck,
                        onValueChange = { monthsToCheck = it },
                        label = { Text("Months to Check") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = preWarmOffset,
                        onValueChange = { preWarmOffset = it },
                        label = { Text("Pre-Warm Offset") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = maxWorkers,
                        onValueChange = { maxWorkers = it },
                        label = { Text("Max Workers") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = restCycleChecks,
                        onValueChange = { restCycleChecks = it },
                        label = { Text("Rest Cycle Checks") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = restCycleDuration,
                        onValueChange = { restCycleDuration = it },
                        label = { Text("Rest Cycle Duration") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Save Button
        Button(
            onClick = {
                viewModel.saveConfig(
                    mode = mode,
                    strikeTime = strikeTime,
                    preferredDays = selectedDays.toList(),
                    ntfyTopic = ntfyTopic,
                    preferredSlug = preferredSlug,
                    checkWindow = checkWindow,
                    checkInterval = checkInterval,
                    requestJitter = requestJitter,
                    monthsToCheck = monthsToCheck.toIntOrNull() ?: Defaults.MONTHS_TO_CHECK,
                    preWarmOffset = preWarmOffset,
                    maxWorkers = maxWorkers.toIntOrNull() ?: Defaults.MAX_WORKERS,
                    restCycleChecks = restCycleChecks.toIntOrNull() ?: Defaults.REST_CYCLE_CHECKS,
                    restCycleDuration = restCycleDuration
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Configuration")
        }
        
        // Save success feedback
        if (saveSuccess) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Configuration saved successfully!",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // Save error feedback
        if (saveError) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Failed to save configuration",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // Extra spacer at bottom to ensure save button is accessible
        Spacer(modifier = Modifier.height(32.dp))
    }
}
