package com.apptcheck.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Admin Config Screen following TECHNICAL_SPEC.md section 7.4.
 * PIN-protected screen for site-specific settings.
 */
@Composable
fun AdminConfigScreen() {
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
        AdminConfigContent()
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
private fun AdminConfigContent() {
    var activeSite by remember { mutableStateOf("spl") }
    var baseUrl by remember { mutableStateOf("https://spl.libcal.com") }
    var availabilityEndpoint by remember { mutableStateOf("/pass/availability/institution") }
    var digital by remember { mutableStateOf(true) }
    var physical by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("0") }
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginEmail by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Admin Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Site Selector
        OutlinedTextField(
            value = activeSite,
            onValueChange = { activeSite = it },
            label = { Text("Active Site") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
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
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Save Button
        Button(
            onClick = { /* TODO: Implement save */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Admin Configuration")
        }
    }
}
