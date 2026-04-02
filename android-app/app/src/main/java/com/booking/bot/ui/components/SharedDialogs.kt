package com.booking.bot.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.booking.bot.data.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Shared dialog components for editing Museums, Credentials, and Bulk Import.
 * Used by both ConfigScreen and WizardScreen as per TECHNICAL_SPEC.md architecture.
 */

/**
 * Dialog for editing a Museum
 */
@Composable
fun MuseumEditDialog(
    museum: Museum?,
    onSave: (Museum) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(museum?.name ?: "") }
    var slug by remember { mutableStateOf(museum?.slug ?: "") }
    var museumId by remember { mutableStateOf(museum?.museumId ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (museum == null) "Add Museum" else "Edit Museum") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it.lowercase().replace("\\s+".toRegex(), "-") },
                    label = { Text("Slug") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = museumId,
                    onValueChange = { museumId = it },
                    label = { Text("Museum ID") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && slug.isNotBlank() && museumId.isNotBlank()) {
                        onSave(Museum(name = name, slug = slug, museumId = museumId))
                    }
                },
                enabled = name.isNotBlank() && slug.isNotBlank() && museumId.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for editing a CredentialSet
 */
@Composable
fun CredentialEditDialog(
    credential: CredentialSet?,
    onSave: (CredentialSet) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(credential?.label ?: "") }
    var username by remember { mutableStateOf(credential?.username ?: "") }
    var password by remember { mutableStateOf(credential?.password ?: "") }
    var email by remember { mutableStateOf(credential?.email ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (credential == null) "Add Credential" else "Edit Credential") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Library Card Number") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("PIN") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.isNotBlank() && username.isNotBlank()) {
                        onSave(
                            credential?.copy(
                                label = label,
                                username = username,
                                password = password,
                                email = email
                            ) ?: CredentialSet(
                                id = UUID.randomUUID().toString(),
                                label = label,
                                username = username,
                                password = password,
                                email = email
                            )
                        )
                    }
                },
                enabled = label.isNotBlank() && username.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for bulk importing museums
 */
@Composable
fun BulkImportDialog(
    existingMuseums: Set<String>,
    onImport: (List<Museum>) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var previewMuseums by remember { mutableStateOf<List<Museum>>(emptyList()) }
    var hasParsed by remember { mutableStateOf(false) }
    var duplicateMuseums by remember { mutableStateOf<List<Museum>>(emptyList()) }
    var showOverwriteConfirmation by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk Import Museums") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste museum data in format: name:slug:museumId (one per line)",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Museum Data") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    maxLines = 10
                )
                Button(
                    onClick = {
                        val museums = inputText.lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                val parts = line.split(":")
                                if (parts.size >= 3) {
                                    Museum(
                                        name = parts[0].trim(),
                                        slug = parts[1].trim().lowercase().replace("\\s+".toRegex(), "-"),
                                        museumId = parts[2].trim()
                                    )
                                } else null
                            }
                        previewMuseums = museums
                        // Check for duplicates (SITE-09)
                        duplicateMuseums = museums.filter { it.slug in existingMuseums }
                        hasParsed = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Preview")
                }
                if (hasParsed) {
                    if (previewMuseums.isEmpty()) {
                        Text("No valid entries found", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Preview (${previewMuseums.size} museums):", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(
                            modifier = Modifier.height(150.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(previewMuseums) { museum ->
                                val isDuplicate = museum.slug in existingMuseums
                                Text(
                                    "${museum.name} (${museum.slug})${if (isDuplicate) " ⚠️ Overwrite" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDuplicate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (duplicateMuseums.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "⚠️ ${duplicateMuseums.size} museum(s) will be overwritten.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (duplicateMuseums.isNotEmpty() && !showOverwriteConfirmation) {
                        showOverwriteConfirmation = true
                    } else {
                        onImport(previewMuseums) 
                    }
                },
                enabled = hasParsed && previewMuseums.isNotEmpty()
            ) {
                Text(if (showOverwriteConfirmation) "Confirm Overwrite" else "Import")
            }
        },
        dismissButton = {
            TextButton(onClick = { 
                if (showOverwriteConfirmation) {
                    showOverwriteConfirmation = false
                } else {
                    onDismiss 
                }
            }) {
                Text(if (showOverwriteConfirmation) "Cancel" else "Cancel")
            }
        }
    )
}

/**
 * Dialog for editing Site configuration
 */
@Composable
fun SiteEditDialog(
    siteKey: String,
    siteConfig: SiteConfig?,
    onSave: (SiteConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var baseUrl by remember { mutableStateOf(siteConfig?.baseUrl ?: "") }
    var availabilityEndpoint by remember { mutableStateOf(siteConfig?.availabilityEndpoint ?: "") }
    var digital by remember { mutableStateOf(siteConfig?.digital ?: false) }
    var physical by remember { mutableStateOf(siteConfig?.physical ?: false) }
    var location by remember { mutableStateOf(siteConfig?.location ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Site: ${siteKey.uppercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = availabilityEndpoint,
                    onValueChange = { availabilityEndpoint = it },
                    label = { Text("Availability Endpoint") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Digital Sessions")
                    Switch(checked = digital, onCheckedChange = { digital = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Physical Sessions")
                    Switch(checked = physical, onCheckedChange = { physical = it })
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (baseUrl.isNotBlank()) {
                        onSave(
                            siteConfig?.copy(
                                baseUrl = baseUrl,
                                availabilityEndpoint = availabilityEndpoint,
                                digital = digital,
                                physical = physical,
                                location = location
                            ) ?: SiteConfig(
                                name = siteKey,
                                baseUrl = baseUrl,
                                availabilityEndpoint = availabilityEndpoint,
                                digital = digital,
                                physical = physical,
                                location = location,
                                museums = emptyMap(),
                                credentials = emptyList()
                            )
                        )
                    }
                },
                enabled = baseUrl.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
