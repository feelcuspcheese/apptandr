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
import com.booking.bot.data.*
import com.booking.bot.ui.components.MuseumEditDialog
import com.booking.bot.ui.components.CredentialEditDialog
import com.booking.bot.ui.components.BulkImportDialog
import com.booking.bot.ui.components.SiteEditDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
                        scope.launch {
                            if (pinEntered == "1234") {
                                showPinDialog = false
                                pinError = false
                            } else {
                                pinError = true
                            }
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

    var mode by remember { mutableStateOf("alert") }
    var strikeTime by remember { mutableStateOf("09:00") }
    var showTimePicker by remember { mutableStateOf(false) }
    var ntfyTopic by remember { mutableStateOf("myappointments") }
    var preferredMuseumSlug by remember { mutableStateOf("") }

    // Performance tuning - initialized to defaults, populated via LaunchedEffect
    var checkWindow     by remember { mutableStateOf(Defaults.CHECK_WINDOW) }
    var checkInterval   by remember { mutableStateOf(Defaults.CHECK_INTERVAL) }
    var requestJitter   by remember { mutableStateOf(Defaults.REQUEST_JITTER) }
    var monthsToCheck   by remember { mutableStateOf(Defaults.MONTHS_TO_CHECK.toString()) }
    var preWarmOffset   by remember { mutableStateOf(Defaults.PRE_WARM_OFFSET) }
    var maxWorkers      by remember { mutableStateOf(Defaults.MAX_WORKERS.toString()) }
    var restCycleChecks by remember { mutableStateOf(Defaults.REST_CYCLE_CHECKS.toString()) }
    var restCycleDuration by remember { mutableStateOf(Defaults.REST_CYCLE_DURATION) }

    // Preferred days
    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    var selectedDays by remember { mutableStateOf(setOf("Monday", "Wednesday", "Friday")) }

    // Museum dropdown state
    var museumExpanded by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    // Sync all local state from DataStore whenever config changes.
    LaunchedEffect(config) {
        config?.general?.let { g ->
            mode              = g.mode
            strikeTime        = g.strikeTime
            ntfyTopic         = g.ntfyTopic
            preferredMuseumSlug = g.preferredMuseumSlug
            checkWindow       = g.checkWindow
            checkInterval     = g.checkInterval
            requestJitter     = g.requestJitter
            monthsToCheck     = g.monthsToCheck.toString()
            preWarmOffset     = g.preWarmOffset
            maxWorkers        = g.maxWorkers.toString()
            restCycleChecks   = g.restCycleChecks.toString()
            restCycleDuration = g.restCycleDuration
            selectedDays      = g.preferredDays.toSet()
        }
    }

    LaunchedEffect(showTimePicker) {
        if (showTimePicker) {
            val parts = strikeTime.split(":")
            val hour   = parts.getOrNull(0)?.toIntOrNull() ?: 9
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            android.app.TimePickerDialog(
                context,
                { _, h, m -> strikeTime = String.format("%02d:%02d", h, m) },
                hour, minute,
                true // 24-hour view
            ).also { dialog ->
                dialog.setOnDismissListener { showTimePicker = false }
                dialog.show()
            }
        }
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selection
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
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
                    
                    // Fixed duration fields: allows letters (s, m, h) and decimals
                    OutlinedTextField(
                        value = checkWindow,
                        onValueChange = { checkWindow = it.filter { char -> char.isLetterOrDigit() || char == '.' } },
                        label = { Text("Check Window (e.g. 60s, 1.5m)") },
                        placeholder = { Text("60s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkInterval,
                        onValueChange = { checkInterval = it.filter { char -> char.isLetterOrDigit() || char == '.' } },
                        label = { Text("Check Interval (e.g. 0.81s)") },
                        placeholder = { Text("0.81s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = requestJitter,
                        onValueChange = { requestJitter = it.filter { char -> char.isLetterOrDigit() || char == '.' } },
                        label = { Text("Request Jitter (e.g. 0.18s, 800ms)") },
                        placeholder = { Text("0.18s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Integer fields remain digits only
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
                        onValueChange = { preWarmOffset = it.filter { char -> char.isLetterOrDigit() || char == '.' } },
                        label = { Text("Pre-Warm Offset (e.g. 30s)") },
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
                        onValueChange = { restCycleDuration = it.filter { char -> char.isLetterOrDigit() || char == '.' } },
                        label = { Text("Rest Cycle Duration (e.g. 3s)") },
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
                        // Helper to ensure durations always have a unit suffix (fallback to 's' if user just types a number)
                        fun formatDuration(input: String, default: String): String {
                            val trimmed = input.trim()
                            if (trimmed.isEmpty()) return default
                            if (trimmed.matches(Regex("^[0-9]+(\\.[0-9]+)?$"))) return "${trimmed}s"
                            return trimmed
                        }

                        val newGeneral = GeneralSettings(
                            mode = mode,
                            strikeTime = strikeTime,
                            preferredDays = selectedDays.toList(),
                            ntfyTopic = ntfyTopic,
                            preferredMuseumSlug = preferredMuseumSlug,
                            // Apply flexible formatting for durations
                            checkWindow = formatDuration(checkWindow, Defaults.CHECK_WINDOW),
                            checkInterval = formatDuration(checkInterval, Defaults.CHECK_INTERVAL),
                            requestJitter = formatDuration(requestJitter, Defaults.REQUEST_JITTER),
                            monthsToCheck = monthsToCheck.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.MONTHS_TO_CHECK,
                            preWarmOffset = formatDuration(preWarmOffset, Defaults.PRE_WARM_OFFSET),
                            maxWorkers = maxWorkers.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.MAX_WORKERS,
                            restCycleChecks = restCycleChecks.toIntOrNull()?.takeIf { it > 0 } ?: Defaults.REST_CYCLE_CHECKS,
                            restCycleDuration = formatDuration(restCycleDuration, Defaults.REST_CYCLE_DURATION)
                        )
                        
                        configManager.updateGeneral(newGeneral)
                        saveFeedback = "Settings saved successfully!"
                        delay(2000)
                        saveFeedback = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save General Settings")
            }
            
            if (saveFeedback != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(saveFeedback!!, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitesTab(
    configManager: ConfigManager,
    config: AppConfig?
) {
    val scope = rememberCoroutineScope()
    
    val activeSite = config?.admin?.activeSite ?: "spl"
    var selectedSiteKey by remember { mutableStateOf(activeSite) }
    var siteExpanded by remember { mutableStateOf(false) }
    
    var showMuseumDialog by remember { mutableStateOf(false) }
    var showCredentialDialog by remember { mutableStateOf(false) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var editingMuseum by remember { mutableStateOf<Museum?>(null) }
    var editingCredential by remember { mutableStateOf<CredentialSet?>(null) }
    var showMuseumDeleteConfirmation by remember { mutableStateOf<Museum?>(null) }
    var showCredentialDeleteConfirmation by remember { mutableStateOf<CredentialSet?>(null) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    
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
                        Text("Editing ${site.name} Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Site Configuration", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var baseUrl by remember { mutableStateOf(site.baseUrl) }
                        var availabilityEndpoint by remember { mutableStateOf(site.availabilityEndpoint) }
                        var digital by remember { mutableStateOf(site.digital) }
                        var physical by remember { mutableStateOf(site.physical) }
                        var location by remember { mutableStateOf(site.location) }
                        
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
                                    val currentConfig = configManager.configFlow.first()
                                    val updatedSites = currentConfig.admin.sites.toMutableMap()
                                    updatedSites[selectedSiteKey] = site.copy(
                                        baseUrl = baseUrl,
                                        availabilityEndpoint = availabilityEndpoint,
                                        digital = digital,
                                        physical = physical,
                                        location = location
                                    )
                                    val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
                                    configManager.updateAdmin(updatedAdmin)
                                    saveFeedback = "Site configuration saved successfully!"
                                    delay(2000)
                                    saveFeedback = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Site Configuration")
                        }
                        
                        if (saveFeedback != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Text(saveFeedback!!, modifier = Modifier.padding(12.dp))
                            }
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
                            IconButton(onClick = { showBulkImportDialog = true }) {
                                Icon(Icons.Default.ImportExport, contentDescription = "Bulk Import")
                            }
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
                                            val currentConfig = config ?: return@launch
                                            val updatedSites = currentConfig.admin.sites.toMutableMap()
                                            val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                                defaultCredentialId = cred.id
                                            )
                                            if (updatedSite != null) {
                                                updatedSites[selectedSiteKey] = updatedSite
                                                val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
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

    if (showMuseumDialog) {
        MuseumEditDialog(
            museum = editingMuseum,
            onSave = { museum ->
                scope.launch {
                    val currentConfig = config ?: return@launch
                    val updatedSites = currentConfig.admin.sites.toMutableMap()
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val updatedMuseums = currentSite.museums.toMutableMap()
                    updatedMuseums[museum.slug] = museum
                    updatedSites[selectedSiteKey] = currentSite.copy(museums = updatedMuseums)
                    val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
                    configManager.updateAdmin(updatedAdmin)
                    showMuseumDialog = false
                    editingMuseum = null
                }
            },
            onDismiss = {
                showMuseumDialog = false
                editingMuseum = null
            }
        )
    }

    if (showCredentialDialog) {
        CredentialEditDialog(
            credential = editingCredential,
            onSave = { credential ->
                scope.launch {
                    val currentConfig = config ?: return@launch
                    val updatedSites = currentConfig.admin.sites.toMutableMap()
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val credentials = currentSite.credentials.toMutableList()
                    if (editingCredential != null) {
                        val index = credentials.indexOfFirst { it.id == credential.id }
                        if (index >= 0) credentials[index] = credential
                    } else {
                        credentials.add(credential)
                    }
                    updatedSites[selectedSiteKey] = currentSite.copy(credentials = credentials)
                    val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
                    configManager.updateAdmin(updatedAdmin)
                    showCredentialDialog = false
                    editingCredential = null
                }
            },
            onDismiss = {
                showCredentialDialog = false
                editingCredential = null
            }
        )
    }

    if (showBulkImportDialog) {
        BulkImportDialog(
            existingMuseums = config?.admin?.sites?.get(selectedSiteKey)?.museums?.keys ?: emptySet(),
            onImport = { museums ->
                scope.launch {
                    val currentConfig = config ?: return@launch
                    val updatedSites = currentConfig.admin.sites.toMutableMap()
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val updatedMuseums = currentSite.museums.toMutableMap()
                    museums.forEach { museum ->
                        updatedMuseums[museum.slug] = museum
                    }
                    updatedSites[selectedSiteKey] = currentSite.copy(museums = updatedMuseums)
                    val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
                    configManager.updateAdmin(updatedAdmin)
                    showBulkImportDialog = false
                }
            },
            onDismiss = {
                showBulkImportDialog = false
            }
        )
    }
    
    showMuseumDeleteConfirmation?.let { museum ->
        AlertDialog(
            onDismissRequest = { showMuseumDeleteConfirmation = null },
            title = { Text("Delete Museum") },
            text = { Text("Delete '${museum.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val currentConfig = config ?: return@launch
                            val updatedSites = currentConfig.admin.sites.toMutableMap()
                            val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                            val updatedMuseums = currentSite.museums.toMutableMap()
                            updatedMuseums.remove(museum.slug)
                            val updatedSite = currentSite.copy(museums = updatedMuseums)
                            updatedSites[selectedSiteKey] = updatedSite
                            val updatedAdmin = currentConfig.admin.copy(sites = updatedSites)
                            
                            val fullConfig = configManager.configFlow.first()
                            val newGeneral = if (fullConfig.general.preferredMuseumSlug == museum.slug) {
                                fullConfig.general.copy(preferredMuseumSlug = "")
                            } else {
                                fullConfig.general
                            }
                            configManager.updateAdmin(updatedAdmin)
                            if (newGeneral != fullConfig.general) {
                                configManager.updateGeneral(newGeneral)
                            }
                            configManager.cleanupInvalidRuns()
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
    
    showCredentialDeleteConfirmation?.let { credential ->
        AlertDialog(
            onDismissRequest = { showCredentialDeleteConfirmation = null },
            title = { Text("Delete Credential") },
            text = { Text("Delete '${credential.label}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val currentConfig = config ?: return@launch
                            val updatedSites = currentConfig.admin.sites.toMutableMap()
                            val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                            val updatedCredentials = currentSite.credentials.toMutableList()
                            updatedCredentials.removeAll { it.id == credential.id }
                            val updatedSite = currentSite.copy(credentials = updatedCredentials)
                            updatedSites[selectedSiteKey] = updatedSite
                            
                            val newDefaultCredentialId = if (currentSite.defaultCredentialId == credential.id) {
                                null
                            } else {
                                currentSite.defaultCredentialId
                            }
                            val finalSite = updatedSite.copy(defaultCredentialId = newDefaultCredentialId)
                            val finalAdmin = currentConfig.admin.copy(sites = updatedSites.toMutableMap() + (selectedSiteKey to finalSite))
                            configManager.updateAdmin(finalAdmin)
                            configManager.cleanupInvalidRuns()
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
