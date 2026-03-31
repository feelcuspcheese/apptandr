package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import com.apptcheck.agent.model.Defaults
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore

/**
 * User Config Screen following TECHNICAL_SPEC.md section 7.3.
 * All user-editable fields with Save button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserConfigScreen() {
    // State variables for all user config fields
    var mode by remember { mutableStateOf("alert") }
    var strikeTime by remember { mutableStateOf("09:00") }
    var ntfyTopic by remember { mutableStateOf("myappointments") }
    var preferredSlug by remember { mutableStateOf("") }
    
    // Performance tuning fields
    var checkWindow by remember { mutableStateOf(Defaults.CHECK_WINDOW) }
    var checkInterval by remember { mutableStateOf(Defaults.CHECK_INTERVAL) }
    var requestJitter by remember { mutableStateOf(Defaults.REQUEST_JITTER) }
    var monthsToCheck by remember { mutableStateOf(Defaults.MONTHS_TO_CHECK.toString()) }
    var preWarmOffset by remember { mutableStateOf(Defaults.PRE_WARM_OFFSET) }
    var maxWorkers by remember { mutableStateOf(Defaults.MAX_WORKERS.toString()) }
    var restCycleChecks by remember { mutableStateOf(Defaults.REST_CYCLE_CHECKS.toString()) }
    var restCycleDuration by remember { mutableStateOf(Defaults.REST_CYCLE_DURATION) }
    
    // Preferred days
    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    var selectedDays by remember { mutableStateOf(setOf("Monday", "Wednesday", "Friday")) }
    
    // Performance section expanded state
    var performanceExpanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
        
        // Preferred Museum Slug
        OutlinedTextField(
            value = preferredSlug,
            onValueChange = { preferredSlug = it },
            label = { Text("Preferred Museum Slug") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
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
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Save Button
        Button(
            onClick = { /* TODO: Implement save */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Configuration")
        }
    }
}
