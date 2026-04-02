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
import androidx.compose.foundation.FlowRow
import com.booking.bot.data.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * First-Run Wizard following TECHNICAL_SPEC.md section 5.5 and test cases WIZ-01 through WIZ-08.
 * Guides user through initial setup: site selection, museum setup, credential setup, general settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    configManager: ConfigManager,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by configManager.configFlow.collectAsState(initial = null)
    
    // Wizard state
    var currentStep by remember { mutableStateOf(0) }
    var wizardCompleted by remember { mutableStateOf(false) }
    
    // Step 1: Site selection
    var selectedSiteKey by remember { mutableStateOf("spl") }
    var showSiteEditDialog by remember { mutableStateOf(false) }
    
    // Step 2: Museum setup
    var showMuseumDialog by remember { mutableStateOf(false) }
    var editingMuseum by remember { mutableStateOf<Museum?>(null) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    
    // Step 3: Credential setup
    var showCredentialDialog by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<CredentialSet?>(null) }
    
    // Step 4: General settings
    var mode by remember { mutableStateOf("alert") }
    var strikeTime by remember { mutableStateOf("09:00") }
    var preferredDays by remember { mutableStateOf(listOf("Monday", "Wednesday", "Friday")) }
    var ntfyTopic by remember { mutableStateOf("myappointments") }
    var preferredMuseumSlug by remember { mutableStateOf<String?>(null) }
    
    // Feedback state
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackSuccess by remember { mutableStateOf(false) }
    
    // Validation state
    var hasAtLeastOneMuseum by remember { mutableStateOf(false) }
    var hasAtLeastOneCredential by remember { mutableStateOf(false) }
    
    // Update validation state when config changes
    LaunchedEffect(config, selectedSiteKey) {
        config?.let { cfg ->
            val site = cfg.admin.sites[selectedSiteKey]
            hasAtLeastOneMuseum = !site?.museums.isNullOrEmpty()
            hasAtLeastOneCredential = !site?.credentials.isNullOrEmpty()
        }
    }
    
    val totalSteps = 4
    
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Welcome to Booking Bot") },
                navigationIcon = {
                    if (currentStep > 0) {
                        IconButton(onClick = { currentStep-- }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Previous")
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = onCancel,
                        enabled = currentStep < totalSteps - 1
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = (currentStep + 1).toFloat() / totalSteps,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Step ${currentStep + 1} of $totalSteps",
                style = MaterialTheme.typography.bodySmall
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Step content
            when (currentStep) {
                0 -> {
                    // Step 1: Site Selection
                    Text("Select and Configure Your Site", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Choose which library system you want to use. You can configure the site settings below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Available Sites", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            config?.admin?.sites?.keys?.forEach { key ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    RadioButton(
                                        selected = selectedSiteKey == key,
                                        onClick = { 
                                            selectedSiteKey = key
                                            config?.admin?.activeSite = key
                                        }
                                    )
                                    Text(key.uppercase(), style = MaterialTheme.typography.bodyLarge)
                                    IconButton(onClick = { showSiteEditDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Site")
                                    }
                                }
                            }
                        }
                    }
                }
                
                1 -> {
                    // Step 2: Museum Setup
                    Text("Add Museums", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Add at least one museum to book appointments from. You can add multiple museums and choose your preferred one later.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
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
                                Text("No museums added yet. Please add at least one museum to continue.", 
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.height(200.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(museums) { museum ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
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
                                                        val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                                                        val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                                            museums = (updatedSites[selectedSiteKey]?.museums?.toMutableMap() ?: mutableMapOf()).apply {
                                                                remove(museum.slug)
                                                            }
                                                        )
                                                        if (updatedSite != null) {
                                                            updatedSites[selectedSiteKey] = updatedSite
                                                            val updatedAdmin = config?.admin?.copy(sites = updatedSites) ?: return@launch
                                                            configManager.updateAdmin(updatedAdmin)
                                                        }
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
                    
                    if (!hasAtLeastOneMuseum) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠️ You must add at least one museum before continuing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                2 -> {
                    // Step 3: Credential Setup
                    Text("Add Credentials", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Add at least one library card credential. This is required to complete the setup.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Credentials", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val site = config?.admin?.sites?.get(selectedSiteKey)
                            val credentials = site?.credentials ?: emptyList()
                            
                            if (credentials.isEmpty()) {
                                Text("No credentials added yet. Please add at least one credential to continue.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.height(200.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(credentials) { cred ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(cred.label, style = MaterialTheme.typography.bodyLarge)
                                                Text(cred.username, style = MaterialTheme.typography.bodySmall)
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
                                                            val updatedAdmin = config?.admin?.copy(sites = updatedSites) ?: return@launch
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
                                                    scope.launch {
                                                        val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                                                        val updatedSite = updatedSites[selectedSiteKey]?.copy(
                                                            credentials = (updatedSites[selectedSiteKey]?.credentials?.toMutableList() ?: mutableListOf()).apply {
                                                                removeAll { it.id == cred.id }
                                                            }
                                                        )
                                                        if (updatedSite != null) {
                                                            updatedSites[selectedSiteKey] = updatedSite
                                                            val updatedAdmin = config?.admin?.copy(sites = updatedSites) ?: return@launch
                                                            
                                                            val newDefaultCredentialId = if (updatedSite.defaultCredentialId == cred.id) null else updatedSite.defaultCredentialId
                                                            val finalSite = updatedSite.copy(defaultCredentialId = newDefaultCredentialId)
                                                            val finalAdmin = updatedAdmin.copy(sites = updatedAdmin.sites.toMutableMap() + (selectedSiteKey to finalSite))
                                                            configManager.updateAdmin(finalAdmin)
                                                        }
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
                    
                    FloatingActionButton(
                        onClick = { editingCredential = null; showCredentialDialog = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Credential")
                    }
                    
                    if (!hasAtLeastOneCredential) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠️ You must add at least one credential before continuing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                3 -> {
                    // Step 4: General Settings
                    Text("General Settings", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Configure your booking preferences. These can be changed later in the Config screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            // Mode selection
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        listOf("alert", "booking").forEach { m ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = mode == m,
                                                    onClick = { mode = m }
                                                )
                                                Text(m.replaceFirstChar { it.uppercase() })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        item {
                            // Strike Time
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Strike Time", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = strikeTime,
                                        onValueChange = { strikeTime = it },
                                        label = { Text("HH:MM format") },
                                        placeholder = { Text("09:00") }
                                    )
                                }
                            }
                        }
                        
                        item {
                            // Preferred Days
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Preferred Days", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").forEach { day ->
                                            FilterChip(
                                                selected = day in preferredDays,
                                                onClick = {
                                                    preferredDays = if (day in preferredDays) {
                                                        preferredDays.filter { it != day }
                                                    } else {
                                                        preferredDays + day
                                                    }
                                                },
                                                label = { Text(day) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        item {
                            // ntfy Topic
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("ntfy Topic", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = ntfyTopic,
                                        onValueChange = { ntfyTopic = it },
                                        label = { Text("ntfy.sh topic") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        item {
                            // Preferred Museum
                            Card {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Preferred Museum (Optional)", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    val site = config?.admin?.sites?.get(selectedSiteKey)
                                    val museums = site?.museums?.values?.toList() ?: emptyList()
                                    
                                    if (museums.isEmpty()) {
                                        Text("No museums available", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        var museumExpanded by remember { mutableStateOf(false) }
                                        ExposedDropdownMenuBox(
                                            expanded = museumExpanded,
                                            onExpandedChange = { museumExpanded = it }
                                        ) {
                                            OutlinedTextField(
                                                value = preferredMuseumSlug?.let { slug -> museums.find { it.slug == slug }?.name } ?: "None",
                                                onValueChange = {},
                                                readOnly = true,
                                                label = { Text("Preferred Museum") },
                                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .menuAnchor()
                                            )
                                            ExposedDropdownMenu(
                                                expanded = museumExpanded,
                                                onDismissRequest = { museumExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("None") },
                                                    onClick = {
                                                        preferredMuseumSlug = null
                                                        museumExpanded = false
                                                    }
                                                )
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
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Feedback message
            if (feedbackMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (feedbackSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        feedbackMessage!!,
                        color = if (feedbackSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep > 0) {
                    OutlinedButton(onClick = { currentStep-- }) {
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.width(100.dp))
                }
                
                Button(
                    onClick = {
                        if (currentStep < totalSteps - 1) {
                            // Validate current step before proceeding
                            when (currentStep) {
                                1 -> {
                                    if (!hasAtLeastOneMuseum) {
                                        feedbackMessage = "Please add at least one museum"
                                        feedbackSuccess = false
                                        return@Button
                                    }
                                }
                                2 -> {
                                    if (!hasAtLeastOneCredential) {
                                        feedbackMessage = "Please add at least one credential"
                                        feedbackSuccess = false
                                        return@Button
                                    }
                                }
                            }
                            currentStep++
                            feedbackMessage = null
                        } else {
                            // Complete wizard
                            scope.launch {
                                try {
                                    // Update admin config with selected site
                                    val updatedAdmin = config?.admin?.copy(activeSite = selectedSiteKey)
                                    if (updatedAdmin != null) {
                                        configManager.updateAdmin(updatedAdmin)
                                    }
                                    
                                    // Update general settings
                                    val newGeneral = GeneralSettings(
                                        mode = mode,
                                        strikeTime = strikeTime,
                                        preferredDays = preferredDays,
                                        ntfyTopic = ntfyTopic,
                                        preferredMuseumSlug = preferredMuseumSlug ?: ""
                                    )
                                    configManager.updateGeneral(newGeneral)
                                    
                                    // Set wizard completed flag
                                    configManager.setWizardCompleted(true)
                                    
                                    feedbackMessage = "Setup complete! Welcome to Booking Bot."
                                    feedbackSuccess = true
                                    wizardCompleted = true
                                    
                                    // Delay slightly to show success message
                                    kotlinx.coroutines.delay(1000)
                                    onComplete()
                                } catch (e: Exception) {
                                    feedbackMessage = "Error saving configuration: ${e.message}"
                                    feedbackSuccess = false
                                }
                            }
                        }
                    },
                    enabled = when (currentStep) {
                        1 -> hasAtLeastOneMuseum
                        2 -> hasAtLeastOneCredential
                        else -> true
                    }
                ) {
                    Text(if (currentStep < totalSteps - 1) "Next" else "Finish")
                }
            }
        }
    }
    
    // Site Edit Dialog
    if (showSiteEditDialog) {
        SiteEditDialog(
            siteKey = selectedSiteKey,
            siteConfig = config?.admin?.sites?.get(selectedSiteKey),
            onSave = { updatedSite ->
                scope.launch {
                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                    updatedSites[selectedSiteKey] = updatedSite
                    val updatedAdmin = config?.admin?.copy(sites = updatedSites)
                    configManager.updateAdmin(updatedAdmin)
                }
                showSiteEditDialog = false
            },
            onDismiss = { showSiteEditDialog = false }
        )
    }
    
    // Museum Edit Dialog
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
                    config?.admin?.let { admin ->
                        configManager.updateAdmin(admin.copy(sites = updatedSites))
                    }
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
    
    // Bulk Import Dialog
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
                    config?.admin?.let { admin ->
                        configManager.updateAdmin(admin.copy(sites = updatedSites))
                    }
                }
                showBulkImportDialog = false
            },
            onDismiss = { showBulkImportDialog = false }
        )
    }
    
    // Credential Edit Dialog
    if (showCredentialDialog) {
        CredentialEditDialog(
            credential = editingCredential,
            onSave = { credential ->
                scope.launch {
                    val updatedSites = config?.admin?.sites?.toMutableMap() ?: return@launch
                    val currentSite = updatedSites[selectedSiteKey] ?: return@launch
                    val updatedCredentials = currentSite.credentials.toMutableList()
                    val existingIndex = updatedCredentials.indexOfFirst { it.id == credential.id }
                    if (existingIndex >= 0) {
                        updatedCredentials[existingIndex] = credential
                    } else {
                        updatedCredentials.add(credential)
                    }
                    updatedSites[selectedSiteKey] = currentSite.copy(credentials = updatedCredentials)
                    config?.admin?.let { admin ->
                        configManager.updateAdmin(admin.copy(sites = updatedSites))
                    }
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
}
