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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.booking.bot.data.*
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
    val context = LocalContext.current
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
                        keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
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
    val scope = rememberCoroutineScope()
    
    var mode by remember { mutableStateOf(config?.general?.mode ?: "alert") }
    var strikeTime by remember { mutableStateOf(config?.general?.strikeTime ?: "09:00") }
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
        // Mode Selection
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == "alert",
                            onClick = { mode = "alert" },
                            label = { Text("Alert") }
                        )
                        FilterChip(
                            selected = mode == "booking",
                            onClick = { mode = "booking" },
                            label = { Text("Booking") }
                        )
                    }
                }
            }
        }
        
        // Strike Time
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Strike Time", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = strikeTime,
                        onValueChange = { strikeTime = it },
                        label = { Text("HH:MM") },
                        placeholder = { Text("09:00") }
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
                        onValueChange = { checkWindow = it },
                        label = { Text("Check Window") },
                        placeholder = { Text("60s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = checkInterval,
                        onValueChange = { checkInterval = it },
                        label = { Text("Check Interval") },
                        placeholder = { Text("0.81s") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = requestJitter,
                        onValueChange = { requestJitter = it },
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
                        onValueChange = { preWarmOffset = it },
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
                        onValueChange = { restCycleDuration = it },
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
                        val newGeneral = GeneralSettings(
                            mode = mode,
                            strikeTime = strikeTime,
                            preferredDays = selectedDays.toList(),
                            ntfyTopic = ntfyTopic,
                            preferredMuseumSlug = preferredMuseumSlug,
                            checkWindow = checkWindow,
                            checkInterval = checkInterval,
                            requestJitter = requestJitter,
                            monthsToCheck = monthsToCheck.toIntOrNull() ?: Defaults.MONTHS_TO_CHECK,
                            preWarmOffset = preWarmOffset,
                            maxWorkers = maxWorkers.toIntOrNull() ?: Defaults.MAX_WORKERS,
                            restCycleChecks = restCycleChecks.toIntOrNull() ?: Defaults.REST_CYCLE_CHECKS,
                            restCycleDuration = restCycleDuration
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitesTab(
    configManager: ConfigManager,
    config: AppConfig?
) {
    val scope = rememberCoroutineScope()
    
    var selectedSiteKey by remember { mutableStateOf(config?.admin?.activeSite ?: "spl") }
    var siteExpanded by remember { mutableStateOf(false) }
    
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
                        Text("Site Configuration", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var baseUrl by remember { mutableStateOf(site.baseUrl) }
                        var availabilityEndpoint by remember { mutableStateOf(site.availabilityEndpoint) }
                        var digital by remember { mutableStateOf(site.digital) }
                        var physical by remember { mutableStateOf(site.physical) }
                        var location by remember { mutableStateOf(site.location) }
                        
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
                                    val updatedSites = config.admin.sites.toMutableMap()
                                    updatedSites[selectedSiteKey] = site.copy(
                                        baseUrl = baseUrl,
                                        availabilityEndpoint = availabilityEndpoint,
                                        digital = digital,
                                        physical = physical,
                                        location = location
                                    )
                                    val updatedAdmin = config.admin.copy(sites = updatedSites)
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
                    Text("Museums", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val site = config?.admin?.sites?.get(selectedSiteKey)
                    val museums = site?.museums?.values?.toList() ?: emptyList()
                    
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
                                IconButton(onClick = { /* Edit museum */ }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        val updatedSites = config.admin.sites.toMutableMap()
                                        val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                            museums = (updatedSites[selectedSiteKey]?.museums?.toMutableMap() ?: mutableMapOf()).apply {
                                                remove(museum.slug)
                                            }
                                        )
                                        if (updatedSite != null) {
                                            updatedSites[selectedSiteKey] = updatedSite
                                            val updatedAdmin = config.admin.copy(sites = updatedSites)
                                            configManager.updateAdmin(updatedAdmin)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                    
                    Text("Bulk import: name:slug:museumId format", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        // Credentials Section
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Credentials", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val site = config?.admin?.sites?.get(selectedSiteKey)
                    val credentials = site?.credentials ?: emptyList()
                    
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
                                        val updatedSites = config.admin.sites.toMutableMap()
                                        val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                            defaultCredentialId = cred.id
                                        )
                                        if (updatedSite != null) {
                                            updatedSites[selectedSiteKey] = updatedSite
                                            val updatedAdmin = config.admin.copy(sites = updatedSites)
                                            configManager.updateAdmin(updatedAdmin)
                                        }
                                    }
                                }) {
                                    Icon(
                                        if (cred.id == site?.defaultCredentialId) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "Set Default"
                                    )
                                }
                                IconButton(onClick = { /* Edit credential */ }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        val updatedSites = config.admin.sites.toMutableMap()
                                        val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                            credentials = (updatedSites[selectedSiteKey]?.credentials?.toMutableList() ?: mutableListOf()).apply {
                                                removeAll { it.id == cred.id }
                                            }
                                        )
                                        if (updatedSite != null) {
                                            updatedSites[selectedSiteKey] = updatedSite
                                            val updatedAdmin = config.admin.copy(sites = updatedSites)
                                            configManager.updateAdmin(updatedAdmin)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                    
                    Text("Add credentials to enable booking", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
