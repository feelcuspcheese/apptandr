package com.booking.bot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.booking.bot.data.*
import com.booking.bot.ui.components.MuseumEditDialog
import com.booking.bot.ui.components.CredentialEditDialog
import com.booking.bot.ui.components.BulkImportDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConfigScreen following TECHNICAL_SPEC.md section 5.2.
 * PIN-protected admin configuration screen with General and Sites tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    // PIN dialog state
    var showPinDialog by remember { mutableStateOf(true) }
    var pinEntered by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    
    // Main config state after PIN verification
    if (!showPinDialog) {
        ConfigContent(configManager, modifier)
    } else {
        // PIN Dialog
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Enter PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinEntered,
                        onValueChange = { pinEntered = it.filter { char -> char.isDigit() } },
                        label = { Text("PIN") },
                        singleLine = true,
                        isError = pinError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        )
                    )
                    if (pinError) {
                        Text(
                            text = "Incorrect PIN",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinEntered == "1234") {
                            showPinDialog = false
                            pinError = false
                        } else {
                            pinError = true
                        }
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConfigContent(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val config by configManager.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("General", "Sites")
    
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        when (selectedTab) {
            0 -> GeneralTab(configManager, config)
            1 -> SitesTab(configManager, config)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GeneralTab(
    configManager: ConfigManager,
    config: AppConfig?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var mode by remember { mutableStateOf(config?.general?.mode ?: "alert") }
    var strikeTime by remember { mutableStateOf(config?.general?.strikeTime ?: "09:00") }
    var showTimePicker by remember { mutableStateOf(false) }
    var ntfyTopic by remember { mutableStateOf(config?.general?.ntfyTopic ?: "myappointments") }
    var preferredMuseumSlug by remember { mutableStateOf(config?.general?.preferredMuseumSlug ?: "") }
    
    // Performance tuning
    var checkWindow by remember { mutableStateOf(config?.general?.checkWindow ?: Defaults.CHECK_WINDOW) }
    var checkInterval by remember { mutableStateOf(config?.general?.checkInterval ?: Defaults.CHECK_INTERVAL) }
    var requestJitter by remember { mutableStateOf(config?.general?.requestJitter ?: Defaults.REQUEST_JITTER) }
    var monthsToCheck by remember { mutableStateOf((config?.general?.monthsToCheck ?: Defaults.MONTHS_TO_CHECK).toString()) }
    var preWarmOffset by remember { mutableStateOf(config?.general?.preWarmOffset ?: Defaults.PRE_WARM_OFFSET) }
    var maxWorkers by remember { mutableStateOf((config?.general?.maxWorkers ?: Defaults.MAX_WORKERS).toString()) }
    var restCycleChecks by remember { mutableStateOf((config?.general?.restCycleChecks ?: Defaults.REST_CYCLE_CHECKS).toString()) }
    var restCycleDuration by remember { mutableStateOf(config?.general?.restCycleDuration ?: Defaults.REST_CYCLE_DURATION) }
    
    // Preferred days
    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    var selectedDays by remember { mutableStateOf(config?.general?.preferredDays?.toSet() ?: setOf("Monday", "Wednesday", "Friday")) }
    
    // Museum dropdown state
    var museumExpanded by remember { mutableStateOf(false) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selection - using SegmentedButton as per spec section 5.2.1
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Using Row with ToggleButtons as fallback for older Compose versions
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = mode == "alert",
                            onClick = { mode = "alert" },
                            label = { Text("Alert") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = mode == "booking",
                            onClick = { mode = "booking" },
                            label = { Text("Booking") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        
        // Strike Time with TimePicker dialog
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Strike Time", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = strikeTime,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("HH:MM") },
                        placeholder = { Text("09:00") },
                        trailingIcon = {
                            IconButton(onClick = { showTimePicker = true }) {
                                Icon(Icons.Default.AccessTime, contentDescription = "Pick time")
                            }
                        }
                    )
                }
            }
        }
        
        // Preferred Days
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preferred Days", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
        }
        
        // ntfy Topic
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ntfy Topic", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ntfyTopic,
                        onValueChange = { ntfyTopic = it },
                        label = { Text("Topic Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // Preferred Museum Dropdown
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preferred Museum", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val activeSite = config?.admin?.activeSite ?: "spl"
                    val museums = config?.admin?.sites?.get(activeSite)?.museums?.values?.toList() ?: emptyList()
                    val selectedMuseum = museums.find { it.slug == preferredMuseumSlug }
                    
                    ExposedDropdownMenuBox(
                        expanded = museumExpanded,
                        onExpandedChange = { museumExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedMuseum?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Museum") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = museumExpanded,
                            onDismissRequest = { museumExpanded = false }
                        ) {
                            museums.forEach { museum ->
                                DropdownMenuItem(
                                    text = { Text(museum.name) },
                                    onClick = {
                                        preferredMuseumSlug = museum.slug
                                        museumExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Performance Tuning Section
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Performance Tuning", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkWindow,
                        onValueChange = { checkWindow = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Check Window") },
                        placeholder = { Text("60s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkInterval,
                        onValueChange = { checkInterval = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Check Interval") },
                        placeholder = { Text("0.81s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = requestJitter,
                        onValueChange = { requestJitter = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Request Jitter") },
                        placeholder = { Text("0.18s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = monthsToCheck,
                        onValueChange = { monthsToCheck = it.filter { char -> char.isDigit() } },
                        label = { Text("Months to Check") },
                        placeholder = { Text("2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = preWarmOffset,
                        onValueChange = { preWarmOffset = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Pre-Warm Offset") },
                        placeholder = { Text("30s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = maxWorkers,
                        onValueChange = { maxWorkers = it.filter { char -> char.isDigit() } },
                        label = { Text("Max Workers") },
                        placeholder = { Text("2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = restCycleChecks,
                        onValueChange = { restCycleChecks = it.filter { char -> char.isDigit() } },
                        label = { Text("Rest Cycle Checks") },
                        placeholder = { Text("12") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = restCycleDuration,
                        onValueChange = { restCycleDuration = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Rest Cycle Duration") },
                        placeholder = { Text("3s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // Save Button
        item {
            Button(
                onClick = {
                    scope.launch {
                        // Validate numeric fields - ensure positive values only (GEN-08)
                        val checkWindowVal = checkWindow.toDoubleOrNull()?.takeIf { it > 0 }?.toString() ?: Defaults.CHECK_WINDOW
                        val checkIntervalVal = checkInterval.toDoubleOrNull()?.takeIf { it > 0 }?.toString() ?: Defaults.CHECK_INTERVAL
                        val requestJitterVal = requestJitter.toDoubleOrNull()?.takeIf { it > 0 }?.toString() ?: Defaults.REQUEST_JITTER
                        val preWarmOffsetVal = preWarmOffset.toDoubleOrNull()?.takeIf { it > 0 }?.toString() ?: Defaults.PRE_WARM_OFFSET
                        val restCycleDurationVal = restCycleDuration.toDoubleOrNull()?.takeIf { it > 0 }?.toString() ?: Defaults.REST_CYCLE_DURATION
                        
                        val newGeneral = GeneralSettings(
                            mode = mode,
                            strikeTime = strikeTime,
                            preferredDays = selectedDays.toList(),
                            ntfyTopic = ntfyTopic,
                            preferredMuseumSlug = preferredMuseumSlug,
                            checkWindow = checkWindowVal,
                            checkInterval = checkIntervalVal,
                            requestJitter = requestJitterVal,
                            monthsToCheck = monthsToCheck.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.MONTHS_TO_CHECK,
                            preWarmOffset = preWarmOffsetVal,
                            maxWorkers = maxWorkers.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.MAX_WORKERS,
                            restCycleChecks = restCycleChecks.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.REST_CYCLE_CHECKS,
                            restCycleDuration = restCycleDurationVal
                        )
                        configManager.updateGeneral(newGeneral)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save General Settings")
            }
        }
    }
    
    // TimePicker Dialog for Strike Time
    if (showTimePicker) {
        val currentTime = remember { 
            Calendar.getInstance().apply {
                val parts = strikeTime.split(":")
                set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 9)
                set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
        }
        
        android.app.TimePickerDialog(
            context,
            { _, hour, minute ->
                strikeTime = String.format("%02d:%02d", hour, minute)
            },
            currentTime.get(Calendar.HOUR_OF_DAY),
            currentTime.get(Calendar.MINUTE),
            true // is24HourView
        ).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitesTab(
    configManager: ConfigManager,
    config: AppConfig?
) {
    val scope = rememberCoroutineScope()
    
    // Handle nullable config with safe calls
    val activeSite = config?.admin?.activeSite ?: "spl"
    var selectedSiteKey by remember { mutableStateOf(activeSite) }
    var siteExpanded by remember { mutableStateOf(false) }
    
    // Dialog states for museums and credentials
    var showMuseumDialog by remember { mutableStateOf(false) }
    var showCredentialDialog by remember { mutableStateOf(false) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var editingMuseum by remember { mutableStateOf<Museum?>(null) }
    var editingCredential by remember { mutableStateOf<CredentialSet?>(null) }
    var showMuseumDeleteConfirmation by remember { mutableStateOf<Museum?>(null) }
    var showCredentialDeleteConfirmation by remember { mutableStateOf<CredentialSet?>(null) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Site Dropdown
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Active Site", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    ExposedDropdownMenuBox(
                        expanded = siteExpanded,
                        onExpandedChange = { siteExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSiteKey.uppercase(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Site") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = siteExpanded,
                            onDismissRequest = { siteExpanded = false }
                        ) {
                            config?.admin?.sites?.keys?.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.uppercase()) },
                                    onClick = {
                                        selectedSiteKey = key
                                        siteExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Site-specific fields
        item {
            val site = config?.admin?.sites?.get(selectedSiteKey)
            if (site != null) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Visual cue for site being edited (SITE-02)
                        Text("Editing ${site.name} Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Site Configuration", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var baseUrl by remember { mutableStateOf(site.baseUrl) }
                        var availabilityEndpoint by remember { mutableStateOf(site.availabilityEndpoint) }
                        var digital by remember { mutableStateOf(site.digital) }
                        var physical by remember { mutableStateOf(site.physical) }
                        var location by remember { mutableStateOf(site.location) }
                        
                        // [FIX (BUG-002)]: Reset form fields when selectedSiteKey changes using LaunchedEffect
                        LaunchedEffect(selectedSiteKey) {
                            baseUrl = site.baseUrl
                            availabilityEndpoint = site.availabilityEndpoint
                            digital = site.digital
                            physical = site.physical
                            location = site.location
                        }
                        
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = availabilityEndpoint,
                            onValueChange = { availabilityEndpoint = it },
                            label = { Text("Availability Endpoint") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = digital, onCheckedChange = { digital = it })
                                Text("Digital")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = physical, onCheckedChange = { physical = it })
                                Text("Physical")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = location,
                            onValueChange = { location = it },
                            label = { Text("Location") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                                    updatedSites[selectedSiteKey] = site.copy(
                                        baseUrl = baseUrl,
                                        availabilityEndpoint = availabilityEndpoint,
                                        digital = digital,
                                        physical = physical,
                                        location = location
                                    )
                                    val updatedAdmin = config.admin!!.copy(sites = updatedSites)
                                    configManager.updateAdmin(updatedAdmin)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Site Configuration")
                        }
                    }
                }
            }
        }
        
        // Museums Section
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Museums", style = MaterialTheme.typography.titleMedium)
                        Row {
                            // Bulk Import Button
                            IconButton(onClick = { showBulkImportDialog = true }) {
                                Icon(Icons.Default.ImportExport, contentDescription = "Bulk Import")
                            }
                            // Add Museum FloatingActionButton (small)
                            FloatingActionButton(
                                onClick = { editingMuseum = null; showMuseumDialog = true },
                                modifier = Modifier.size(40.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Museum", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val site = config?.admin?.sites?.get(selectedSiteKey)
                    val museums = site?.museums?.values?.toList() ?: emptyList()
                    
                    if (museums.isEmpty()) {
                        Text("No museums added yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        museums.forEach { museum ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(museum.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("${museum.slug} (${museum.museumId})", style = MaterialTheme.typography.bodySmall)
                                }
                                Row {
                                    IconButton(onClick = { editingMuseum = museum; showMuseumDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            // Show confirmation dialog before deleting museum (SITE-06)
                                            // we'll create an inline AlertDialog using a var
                                            // For simplicity, we'll use a direct deletion with confirmation via a separate composable
                                            showMuseumDeleteConfirmation = museum
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Credentials Section
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Credentials", style = MaterialTheme.typography.titleMedium)
                        // Add Credential FloatingActionButton (small)
                        FloatingActionButton(
                            onClick = { editingCredential = null; showCredentialDialog = true },
                            modifier = Modifier.size(40.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Credential", modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val site = config?.admin?.sites?.get(selectedSiteKey)
                    val credentials = site?.credentials ?: emptyList()
                    
                    if (credentials.isEmpty()) {
                        Text("No credentials added yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        credentials.forEach { cred ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(cred.label, style = MaterialTheme.typography.bodyLarge)
                                    Text("${cred.username} ••••••", style = MaterialTheme.typography.bodySmall)
                                }
                                Row {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                                            val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                                defaultCredentialId = cred.id
                                            )
                                            if (updatedSite != null) {
                                                updatedSites[selectedSiteKey] = updatedSite
                                                val updatedAdmin = config.admin!!.copy(sites = updatedSites)
                                                configManager.updateAdmin(updatedAdmin)
                                            }
                                        }
                                    }) {
                                        Icon(
                                            if (cred.id == site?.defaultCredentialId) Icons.Filled.Star else Icons.Filled.StarBorder,
                                            contentDescription = "Set Default"
                                        )
                                    }
                                    IconButton(onClick = { editingCredential = cred; showCredentialDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = {
                                        // Show confirmation dialog before deleting credential (SITE-13)
                                        showCredentialDeleteConfirmation = cred
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Museum Edit Dialog - Render dialog when showMuseumDialog is true (section 5.2.2)
    if (showMuseumDialog) {
        MuseumEditDialog(
            museum = editingMuseum,
            onSave = { museum ->
                scope.launch {
                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val updatedMuseums = currentSite.museums.toMutableMap()
                    updatedMuseums[museum.slug] = museum
                    updatedSites[selectedSiteKey] = currentSite.copy(museums = updatedMuseums)
                    val updatedAdmin = config.admin!!
                        configManager.updateAdmin(updatedAdmin.copy(sites = updatedSites))
                }
                showMuseumDialog = false
                editingMuseum = null
            },
            onDismiss = {
                showMuseumDialog = false
                editingMuseum = null
            }
        )
    }

    // Credential Edit Dialog - Render dialog when showCredentialDialog is true (section 5.2.2)
    if (showCredentialDialog) {
        CredentialEditDialog(
            credential = editingCredential,
            onSave = { credential ->
                scope.launch {
                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val credentials = currentSite.credentials.toMutableList()
                    if (editingCredential != null) {
                        val index = credentials.indexOfFirst { it.id == credential.id }
                        if (index >= 0) credentials[index] = credential
                    } else {
                        credentials.add(credential)
                    }
                    updatedSites[selectedSiteKey] = currentSite.copy(credentials = credentials)
                    val updatedAdmin = config.admin!!
                        configManager.updateAdmin(updatedAdmin.copy(sites = updatedSites))
                }
                showCredentialDialog = false
                editingCredential = null
            },
            onDismiss = {
                showCredentialDialog = false
                editingCredential = null
            }
        )
    }

    // Bulk Import Dialog - Render dialog when showBulkImportDialog is true (section 5.2.2)
    if (showBulkImportDialog) {
        BulkImportDialog(
            existingMuseums = config?.admin?.sites?.get(selectedSiteKey)?.museums?.keys ?: emptySet(),
            onImport = { museums ->
                scope.launch {
                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val updatedMuseums = currentSite.museums.toMutableMap()
                    museums.forEach { museum ->
                        updatedMuseums[museum.slug] = museum
                    }
                    updatedSites[selectedSiteKey] = currentSite.copy(museums = updatedMuseums)
                    val updatedAdmin = config.admin!!
                        configManager.updateAdmin(updatedAdmin.copy(sites = updatedSites))
                }
                showBulkImportDialog = false
            },
            onDismiss = {
                showBulkImportDialog = false
            }
        )
    }
    
    // Museum Delete Confirmation Dialog (SITE-06)
    showMuseumDeleteConfirmation?.let { museum ->
        AlertDialog(
            onDismissRequest = { showMuseumDeleteConfirmation = null },
            title = { Text("Delete Museum") },
            text = { Text("Delete '${museum.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                            val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                museums = (updatedSites[selectedSiteKey]?.museums?.toMutableMap() ?: mutableMapOf()).apply {
                                    remove(museum.slug)
                                }
                            )
                            if (updatedSite != null) {
                                updatedSites[selectedSiteKey] = updatedSite
                                val updatedAdmin = config.admin!!.copy(sites = updatedSites)
                                
                                // Referential integrity: clear preferredMuseumSlug if deleted museum was preferred
                                val currentConfig = configManager.configFlow.first()
                                val newGeneral = if (currentConfig.general.preferredMuseumSlug == museum.slug) {
                                    currentConfig.general.copy(preferredMuseumSlug = "")
                                } else {
                                    currentConfig.general
                                }
                                configManager.updateAdmin(updatedAdmin)
                                if (newGeneral != currentConfig.general) {
                                    configManager.updateGeneral(newGeneral)
                                }
                                // Proactive cleanup: remove invalid scheduled runs (PROP-02, EDGE-09, EDGE-10)
                                configManager.cleanupInvalidRuns()
                            }
                            showMuseumDeleteConfirmation = null
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMuseumDeleteConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Credential Delete Confirmation Dialog (SITE-13)
    showCredentialDeleteConfirmation?.let { credential ->
        AlertDialog(
            onDismissRequest = { showCredentialDeleteConfirmation = null },
            title = { Text("Delete Credential") },
            text = { Text("Delete '${credential.label}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                            val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                credentials = (updatedSites[selectedSiteKey]?.credentials?.toMutableList() ?: mutableListOf()).apply {
                                    removeAll { it.id == credential.id }
                                }
                            )
                            if (updatedSite != null) {
                                updatedSites[selectedSiteKey] = updatedSite
                                val updatedAdmin = config.admin!!.copy(sites = updatedSites)
                                
                                // Referential integrity: clear defaultCredentialId if deleted credential was default
                                val siteConfig = updatedAdmin.sites[selectedSiteKey]
                                val newDefaultCredentialId = if (siteConfig?.defaultCredentialId == credential.id) {
                                    null
                                } else {
                                    siteConfig?.defaultCredentialId
                                }
                                val finalSite = siteConfig?.copy(defaultCredentialId = newDefaultCredentialId)
                                val finalAdmin = if (finalSite != null) {
                                    updatedAdmin.copy(sites = updatedAdmin.sites.toMutableMap() + (selectedSiteKey to finalSite))
                                } else {
                                    updatedAdmin
                                }
                                configManager.updateAdmin(finalAdmin)
                                // Proactive cleanup: remove invalid scheduled runs (PROP-02, EDGE-09, EDGE-10)
                                configManager.cleanupInvalidRuns()
                            }
                            showCredentialDeleteConfirmation = null
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialDeleteConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
