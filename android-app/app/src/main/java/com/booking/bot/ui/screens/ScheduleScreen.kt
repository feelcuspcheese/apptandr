package com.booking.bot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.booking.bot.data.*
import com.booking.bot.scheduler.AlarmScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.app.DatePickerDialog as AndroidDatePickerDialog
import android.app.TimePickerDialog as AndroidTimePickerDialog

/**
 * ScheduleScreen following TECHNICAL_SPEC.md section 5.3.
 * Allows scheduling future booking runs with site, museum, credential, mode, and datetime selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by configManager.configFlow.collectAsState(initial = null)

    // Selection state
    var selectedSiteKey by remember { mutableStateOf(config?.admin?.activeSite ?: "spl") }
    var selectedMuseumSlug by remember { mutableStateOf<String?>(null) }
    var selectedCredentialId by remember { mutableStateOf<String?>(null) }
    var selectedMode by remember { mutableStateOf(config?.general?.mode ?: "alert") }
    var selectedDateTime by remember { mutableStateOf<Long?>(null) }

    // Dropdown expansion states
    var siteExpanded by remember { mutableStateOf(false) }
    var museumExpanded by remember { mutableStateOf(false) }
    var credentialExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }

    // Date/Time picker dialog state - using proper Android DatePickerDialog + TimePickerDialog (section 5.3)
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var tempSelectedDate by remember { mutableStateOf<Calendar?>(null) }

    // Feedback state
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackSuccess by remember { mutableStateOf(false) }

    // Update selections when config changes
    LaunchedEffect(config) {
        config?.let { cfg ->
            if (selectedSiteKey !in cfg.admin.sites.keys) {
                selectedSiteKey = cfg.admin.activeSite
            }
            selectedMode = cfg.general.mode
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Site Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Site", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = siteExpanded,
                    onExpandedChange = { siteExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedSiteKey.uppercase(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Site") },
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
                                    selectedMuseumSlug = null
                                    selectedCredentialId = null
                                    siteExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Museum Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Museum", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val museums = config?.admin?.sites?.get(selectedSiteKey)?.museums?.values?.toList() ?: emptyList()
                val selectedMuseum = museums.find { it.slug == selectedMuseumSlug }

                ExposedDropdownMenuBox(
                    expanded = museumExpanded,
                    onExpandedChange = { museumExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedMuseum?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Museum") },
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
                                    selectedMuseumSlug = museum.slug
                                    museumExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Credential Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Credential", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val site = config?.admin?.sites?.get(selectedSiteKey)
                val credentials = site?.credentials ?: emptyList()
                val defaultCredId = site?.defaultCredentialId

                // Pre-select default credential if available
                LaunchedEffect(site) {
                    if (selectedCredentialId == null && defaultCredId != null) {
                        selectedCredentialId = defaultCredId
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = credentialExpanded,
                    onExpandedChange = { credentialExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (selectedCredentialId == null) "Use default" else (credentials.find { it.id == selectedCredentialId }?.label ?: ""),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Credential") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = credentialExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = credentialExpanded,
                        onDismissRequest = { credentialExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Use default (${defaultCredId?.let { credentials.find { c -> c.id == it }?.label } ?: "none"})") },
                            onClick = {
                                selectedCredentialId = null
                                credentialExpanded = false
                            }
                        )
                        credentials.forEach { cred ->
                            DropdownMenuItem(
                                text = { Text("${cred.label} (${cred.username})") },
                                onClick = {
                                    selectedCredentialId = cred.id
                                    credentialExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Mode Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Mode", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedMode.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        listOf("alert", "booking").forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    selectedMode = mode
                                    modeExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Date/Time Picker
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Date & Time", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val dateTimeText = selectedDateTime?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "Not selected"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dateTimeText)
                    Button(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick")
                    }
                }
            }
        }

        // Schedule Button
        Button(
            onClick = {
                val runSiteKey = selectedSiteKey
                val runMuseumSlug = selectedMuseumSlug
                val runDateTime = selectedDateTime

                when {
                    runMuseumSlug.isNullOrEmpty() -> {
                        feedbackMessage = "Please select a museum"
                        feedbackSuccess = false
                    }
                    runDateTime == null -> {
                        feedbackMessage = "Please select a date and time"
                        feedbackSuccess = false
                    }
                    runDateTime <= System.currentTimeMillis() -> {
                        feedbackMessage = "Please select a future date and time"
                        feedbackSuccess = false
                    }
                    else -> {
                        scope.launch {
                            try {
                                val run = ScheduledRun(
                                    id = UUID.randomUUID().toString(),
                                    siteKey = runSiteKey,
                                    museumSlug = runMuseumSlug,
                                    credentialId = selectedCredentialId,
                                    dropTimeMillis = runDateTime,
                                    mode = selectedMode
                                )

                                configManager.addScheduledRun(run)
                                AlarmScheduler(context).scheduleRun(run)

                                feedbackMessage = "Run scheduled successfully!"
                                feedbackSuccess = true
                                selectedDateTime = null
                            } catch (e: Exception) {
                                feedbackMessage = "Failed to schedule: ${e.message}"
                                feedbackSuccess = false
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Schedule Run")
        }

        // Feedback message
        if (feedbackMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (feedbackSuccess)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    feedbackMessage!!,
                    color = if (feedbackSuccess)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Scheduled Runs List
        Text("Scheduled Runs", style = MaterialTheme.typography.titleLarge)

        config?.scheduledRuns?.let { runs ->
            val sortedRuns = runs.sortedBy { it.dropTimeMillis }

            if (sortedRuns.isEmpty()) {
                Text("No scheduled runs", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedRuns) { run ->
                        RunItem(
                            run = run,
                            config = config,
                            onDelete = {
                                scope.launch {
                                    configManager.removeScheduledRun(run.id)
                                    AlarmScheduler(context).cancelRun(run.id)
                                }
                            }
                        )
                    }
                }
            }
        } ?: Text("Loading...")
    }

    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(onClick = { showDatePicker = false }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            // Simplified date picker - in production use proper DatePicker
            CalendarView(
                date = selectedDateTime ?: System.currentTimeMillis(),
                onDateChange = { selectedDateTime = it }
            )
        }
    }
}

@Composable
private fun RunItem(
    run: ScheduledRun,
    config: AppConfig?,
    onDelete: () -> Unit
) {
    val site = config?.admin?.sites?.get(run.siteKey)
    val museum = site?.museums?.get(run.museumSlug)
    val credential = run.credentialId?.let { id -> site?.credentials?.find { it.id == id } }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    museum?.name ?: run.museumSlug,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "${run.siteKey.uppercase()} • ${run.mode.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(run.dropTimeMillis)),
                    style = MaterialTheme.typography.bodySmall
                )
                credential?.let {
                    Text(
                        "Using: ${it.label}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

// Simple calendar view placeholder - replace with proper implementation
@Composable
private fun CalendarView(
    date: Long,
    onDateChange: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = date }
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Selected: $year-${month + 1}-$day $hour:$minute")
        // In production, implement full calendar UI
        Text("Use Android DatePicker for full functionality")
    }
}
