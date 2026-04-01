package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apptcheck.agent.viewmodel.AdminConfigViewModel
import kotlinx.coroutines.launch

/**
 * Admin Config Screen following TECHNICAL_SPEC.md section 7.4.
 * PIN-protected screen for site-specific settings.
 * Integrated with AdminConfigViewModel for persistent state across navigation.
 * 
 * Supports configuring museums by site - museums are imported in bulk format:
 * name:slug:id (one per line)
 */
@Composable
fun AdminConfigScreen(viewModel: AdminConfigViewModel = viewModel()) {
    var showPinDialog by remember { mutableStateOf(true) }
    var pinInput by remember { mutableStateOf("") }
    var isAuthenticated by remember { mutableStateOf(false) }
    
    if (showPinDialog && !isAuthenticated) {
        PinDialog(
            onConfirm = { pin ->
                if (pin == "1234") {
                    isAuthenticated = true
                    showPinDialog = false
                }
            },
            onDismiss = { showPinDialog = false }
        )
    }
    
    if (isAuthenticated) {
        AdminConfigContent(viewModel)
    }
}

@Composable
private fun PinDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN Required") },
        text = {
            Column {
                Text("Enter admin PIN:")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    singleLine = true,
                    placeholder = { Text("Enter PIN") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(pin) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AdminConfigContent(viewModel: AdminConfigViewModel) {
    // Observe config from ViewModel
    val adminConfig by viewModel.adminConfig.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    
    // Local state variables bound to ViewModel state
    var activeSite by remember { mutableStateOf(adminConfig.activeSite) }
    var baseUrl by remember { mutableStateOf(adminConfig.sites[activeSite]?.baseUrl ?: "") }
    var availabilityEndpoint by remember { mutableStateOf(adminConfig.sites[activeSite]?.availabilityEndpoint ?: "") }
    var digital by remember { mutableStateOf(adminConfig.sites[activeSite]?.digital ?: true) }
    var physical by remember { mutableStateOf(adminConfig.sites[activeSite]?.physical ?: false) }
    var location by remember { mutableStateOf(adminConfig.sites[activeSite]?.location ?: "0") }
    var loginUsername by remember { mutableStateOf(adminConfig.sites[activeSite]?.loginUsername ?: "") }
    var loginPassword by remember { mutableStateOf(adminConfig.sites[activeSite]?.loginPassword ?: "") }
    var loginEmail by remember { mutableStateOf(adminConfig.sites[activeSite]?.loginEmail ?: "") }
    
    // Track sites map
    var sites by remember { mutableStateOf(adminConfig.sites) }
    
    // Museum bulk import text field
    var museumImportText by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importSuccess by remember { mutableStateOf(false) }
    
    // Update local state when ViewModel state changes
    LaunchedEffect(adminConfig) {
        activeSite = adminConfig.activeSite
        baseUrl = adminConfig.sites[activeSite]?.baseUrl ?: ""
        availabilityEndpoint = adminConfig.sites[activeSite]?.availabilityEndpoint ?: ""
        digital = adminConfig.sites[activeSite]?.digital ?: true
        physical = adminConfig.sites[activeSite]?.physical ?: false
        location = adminConfig.sites[activeSite]?.location ?: "0"
        loginUsername = adminConfig.sites[activeSite]?.loginUsername ?: ""
        loginPassword = adminConfig.sites[activeSite]?.loginPassword ?: ""
        loginEmail = adminConfig.sites[activeSite]?.loginEmail ?: ""
        sites = adminConfig.sites.toMutableMap()
        // Load current museums into import text for editing
        museumImportText = adminConfig.sites[activeSite]?.museums?.values?.joinToString("\n") { 
            "${it.name}:${it.slug}:${it.museumId}" 
        } ?: ""
    }
    
    // Update site-specific fields when activeSite changes
    LaunchedEffect(activeSite) {
        baseUrl = adminConfig.sites[activeSite]?.baseUrl ?: ""
        availabilityEndpoint = adminConfig.sites[activeSite]?.availabilityEndpoint ?: ""
        digital = adminConfig.sites[activeSite]?.digital ?: true
        physical = adminConfig.sites[activeSite]?.physical ?: false
        location = adminConfig.sites[activeSite]?.location ?: "0"
        loginUsername = adminConfig.sites[activeSite]?.loginUsername ?: ""
        loginPassword = adminConfig.sites[activeSite]?.loginPassword ?: ""
        loginEmail = adminConfig.sites[activeSite]?.loginEmail ?: ""
        sites = adminConfig.sites.toMutableMap()
        // Load current museums into import text for editing
        museumImportText = adminConfig.sites[activeSite]?.museums?.values?.joinToString("\n") { 
            "${it.name}:${it.slug}:${it.museumId}" 
        } ?: ""
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Admin Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Site Selector Dropdown
        var siteExpanded by remember { mutableStateOf(false) }
        val availableSites = listOf("spl", "kcls")
        
        ExposedDropdownMenuBox(
            expanded = siteExpanded,
            onExpandedChange = { siteExpanded = !siteExpanded }
        ) {
            OutlinedTextField(
                value = activeSite,
                onValueChange = {},
                readOnly = true,
                label = { Text("Active Site") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteExpanded) },
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = siteExpanded,
                onDismissRequest = { siteExpanded = false }
            ) {
                availableSites.forEach { site ->
                    DropdownMenuItem(
                        text = { Text(site.uppercase()) },
                        onClick = {
                            activeSite = site
                            siteExpanded = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Base URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Availability Endpoint
        OutlinedTextField(
            value = availabilityEndpoint,
            onValueChange = { availabilityEndpoint = it },
            label = { Text("Availability Endpoint") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Digital Checkbox
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(
                checked = digital,
                onCheckedChange = { digital = it }
            )
            Text("Digital")
        }
        
        // Physical Checkbox
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(
                checked = physical,
                onCheckedChange = { physical = it }
            )
            Text("Physical")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Location
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("Location") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Museums Section with Bulk Import
        Text("Museums (Bulk Import)", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Enter museums in format: name:slug:id (one per line)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = museumImportText,
            onValueChange = { museumImportText = it },
            label = { Text("Museums (one per line)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            placeholder = { Text("Seattle Art Museum:seattle-art-museum:7f2ac5c414b2") },
            minLines = 5,
            maxLines = 10
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Import button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val currentSite = sites[activeSite]
                    if (currentSite != null) {
                        val newMuseums = mutableMapOf<String, com.apptcheck.agent.model.Museum>()
                        val lines = museumImportText.split("\n").filter { it.isNotBlank() }
                        var parseError: String? = null
                        
                        lines.forEach { line ->
                            val parts = line.split(":")
                            if (parts.size >= 3) {
                                val name = parts[0].trim()
                                val slug = parts[1].trim()
                                val id = parts[2].trim()
                                if (name.isNotEmpty() && slug.isNotEmpty() && id.isNotEmpty()) {
                                    newMuseums[slug] = com.apptcheck.agent.model.Museum(name, slug, id)
                                } else {
                                    parseError = "Invalid format on line: $line"
                                }
                            } else {
                                parseError = "Invalid format on line: $line (expected name:slug:id)"
                            }
                        }
                        
                        if (parseError == null) {
                            val updatedSite = currentSite.copy(museums = newMuseums)
                            sites[activeSite] = updatedSite
                            importMessage = "Parsed ${newMuseums.size} museums successfully"
                            importSuccess = true
                        } else {
                            importMessage = parseError
                            importSuccess = false
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Parse Museums")
            }
            
            Button(
                onClick = {
                    // Reset to default museums for this site
                    museumImportText = when (activeSite) {
                        "spl" -> "Seattle Art Museum:seattle-art-museum:7f2ac5c414b2\nWoodland Park Zoo:zoo:033bbf08993f"
                        "kcls" -> "KidsQuest Children's Museum:kidsquest:9ec25160a8a0"
                        else -> ""
                    }
                    importMessage = "Reset to defaults"
                    importSuccess = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text("Reset to Defaults")
            }
        }
        
        // Import message feedback
        if (importMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (importSuccess) 
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
                        importMessage!!,
                        color = if (importSuccess)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        // Display current museums list
        val currentMuseums = sites[activeSite]?.museums ?: emptyMap()
        if (currentMuseums.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Current Museums (${currentMuseums.size}):", style = MaterialTheme.typography.bodyMedium)
            currentMuseums.values.forEach { museum ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${museum.name} (${museum.slug})", style = MaterialTheme.typography.bodySmall)
                    Text(museum.museumId, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Login Credentials
        Text("Login Credentials", style = MaterialTheme.typography.titleMedium)
        
        OutlinedTextField(
            value = loginUsername,
            onValueChange = { loginUsername = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = loginPassword,
            onValueChange = { loginPassword = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = loginEmail,
            onValueChange = { loginEmail = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Save Button
        Button(
            onClick = {
                // Update the site config with current values including parsed museums
                val currentSite = sites[activeSite]
                if (currentSite != null) {
                    val updatedSite = currentSite.copy(
                        baseUrl = baseUrl,
                        availabilityEndpoint = availabilityEndpoint,
                        digital = digital,
                        physical = physical,
                        location = location,
                        loginUsername = loginUsername,
                        loginPassword = loginPassword,
                        loginEmail = loginEmail
                    )
                    sites[activeSite] = updatedSite
                    viewModel.saveConfig(activeSite, sites)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Admin Configuration")
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
                        "Admin configuration saved successfully!",
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
                        "Failed to save admin configuration",
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
