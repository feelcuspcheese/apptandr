package com.booking.bot.ui.screens

import android.app.DatePickerDialog as AndroidDatePickerDialog
import android.app.TimePickerDialog as AndroidTimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booking.bot.data.*
import com.booking.bot.scheduler.AlarmScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

/**
 * ScheduleScreen following TECHNICAL_SPEC.md section 5.3.
 * 
 * v1.1 Enhancements: Independent locking & Contradiction check.
 * v1.2 Enhancements: Recurring Schedule options.
 * v1.4 Enhancements: Smart Grouping (Headers) & Duplicate Run Protection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(
    configManager: ConfigManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by configManager.configFlow.collectAsState(initial = null)

    var selectedSiteKey by remember { mutableStateOf(config?.admin?.activeSite ?: "spl") }
    var selectedMuseumSlug by remember { mutableStateOf<String?>(null) }
    var selectedCredentialId by remember { mutableStateOf<String?>(null) }
    var selectedMode by remember { mutableStateOf(config?.general?.mode ?: "alert") }
    var selectedDateTime by remember { mutableStateOf<Long?>(null) }
    var selectedTimezone by remember { mutableStateOf(java.util.TimeZone.getDefault().id) }

    // Run-specific overrides (Independence Locking)
    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    var runPreferredDays by remember { mutableStateOf(setOf<String>()) }
    var runPreferredDates by remember { mutableStateOf(setOf<String>()) }

    // Recurring State (v1.2)
    var isRecurring by remember { mutableStateOf(false) }
    var occurrenceLimit by remember { mutableStateOf("") }
    var stopDateMillis by remember { mutableStateOf<Long?>(null) }
    var showStopDatePicker by remember { mutableStateOf(false) }

    var siteExpanded by remember { mutableStateOf(false) }
    var museumExpanded by remember { mutableStateOf(false) }
    var credentialExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }
    var timezoneExpanded by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var tempSelectedDate by remember { mutableStateOf<Calendar?>(null) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        config?.let { cfg ->
            if (selectedSiteKey !in cfg.admin.sites.keys) {
                selectedSiteKey = cfg.admin.activeSite
            }
            if (runPreferredDays.isEmpty() && runPreferredDates.isEmpty()) {
                selectedMode = cfg.general.mode
                runPreferredDays = cfg.general.preferredDays.toSet()
                runPreferredDates = cfg.general.preferredDates.toSet()
            }
        }
    }

    LaunchedEffect(Unit) {
        configManager.cleanupInvalidRuns()
    }

    LaunchedEffect(showDatePicker) {
        if (showDatePicker) {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = selectedDateTime ?: System.currentTimeMillis()
            }
            AndroidDatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    tempSelectedDate = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth)
                    }
                    showTimePicker = true
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).also { dialog ->
                dialog.setOnDismissListener { showDatePicker = false }
                dialog.show()
            }
        }
    }

    LaunchedEffect(showTimePicker) {
        if (showTimePicker) {
            val calendar = tempSelectedDate ?: Calendar.getInstance()
            AndroidTimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    selectedDateTime = calendar.timeInMillis
                    tempSelectedDate = null
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).also { dialog ->
                dialog.setOnDismissListener { showTimePicker = false }
                dialog.show()
            }
        }
    }

    // Stop Date Picker UI for Recurring runs
    if (showStopDatePicker) {
        val calendar = Calendar.getInstance()
        AndroidDatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val cal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 23, 59)
                }
                stopDateMillis = cal.timeInMillis
                showStopDatePicker = false
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Site Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Site", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(expanded = siteExpanded, onExpandedChange = { siteExpanded = it }) {
                    OutlinedTextField(
                        value = selectedSiteKey.uppercase(), onValueChange = {}, readOnly = true,
                        label = { Text("Site") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = siteExpanded, onDismissRequest = { siteExpanded = false }) {
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
                ExposedDropdownMenuBox(expanded = museumExpanded, onExpandedChange = { museumExpanded = it }) {
                    OutlinedTextField(
                        value = selectedMuseum?.name ?: "", onValueChange = {}, readOnly = true,
                        label = { Text("Museum") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = museumExpanded, onDismissRequest = { museumExpanded = false }) {
                        museums.forEach { museum ->
                            DropdownMenuItem(text = { Text(museum.name) }, onClick = {
                                selectedMuseumSlug = museum.slug; museumExpanded = false
                            })
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
                ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
                    OutlinedTextField(
                        value = selectedMode.replaceFirstChar { it.uppercase() }, onValueChange = {}, readOnly = true,
                        label = { Text("Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf("alert", "booking").forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.replaceFirstChar { it.uppercase() }) },
                                onClick = { selectedMode = mode; modeExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // Recurring Schedule Options (v1.2)
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRecurring, onCheckedChange = { isRecurring = it })
                    Text("Daily Recurring Schedule", style = MaterialTheme.typography.titleMedium)
                }
                if (isRecurring) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = occurrenceLimit,
                        onValueChange = { occurrenceLimit = it.filter { char -> char.isDigit() } },
                        label = { Text("Stop after X runs (Optional)") },
                        placeholder = { Text("Leave empty for no run limit") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Stop after Date", style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = stopDateMillis?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) } ?: "No end date",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Row {
                            TextButton(onClick = { showStopDatePicker = true }) {
                                Text("Set")
                            }
                            if (stopDateMillis != null) {
                                IconButton(onClick = { stopDateMillis = null }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear date")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Execution Preferences (Independent Locking)
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Execution Preferences", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Preferred Days", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allDays.forEach { day ->
                        FilterChip(
                            selected = day in runPreferredDays,
                            onClick = {
                                runPreferredDays = if (day in runPreferredDays) runPreferredDays - day else runPreferredDays + day
                            },
                            label = { Text(day) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Preferred Dates", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = {
                        val cal = Calendar.getInstance()
                        AndroidDatePickerDialog(context, { _, y, m, d ->
                            val date = LocalDate.of(y, m + 1, d)
                            runPreferredDates = runPreferredDates + date.toString()
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }) {
                        Icon(Icons.Default.CalendarMonth, "Add Date", modifier = Modifier.size(20.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    runPreferredDates.sorted().forEach { dateIso ->
                        InputChip(
                            selected = true,
                            onClick = { runPreferredDates = runPreferredDates - dateIso },
                            label = { Text(dateIso) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) }
                        )
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
                LaunchedEffect(site) {
                    if (selectedCredentialId == null && defaultCredId != null) {
                        selectedCredentialId = defaultCredId
                    }
                }
                ExposedDropdownMenuBox(expanded = credentialExpanded, onExpandedChange = { credentialExpanded = it }) {
                    OutlinedTextField(
                        value = if (selectedCredentialId == null) "Use default" else (credentials.find { it.id == selectedCredentialId }?.label ?: ""),
                        onValueChange = {}, readOnly = true,
                        label = { Text("Credential") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = credentialExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = credentialExpanded, onDismissRequest = { credentialExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Use default (${defaultCredId?.let { credentials.find { c -> c.id == it }?.label } ?: "none"})") },
                            onClick = { selectedCredentialId = null; credentialExpanded = false }
                        )
                        credentials.forEach { cred ->
                            DropdownMenuItem(
                                text = { Text("${cred.label} (${cred.username})") },
                                onClick = { selectedCredentialId = cred.id; credentialExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // Timezone Dropdown
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Timezone", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val timezones = listOf(
                    "America/Los_Angeles" to "PST/PDT (Los Angeles)",
                    "America/Denver" to "MST/MDT (Denver)",
                    "America/Chicago" to "CST/CDT (Chicago)",
                    "America/New_York" to "EST/EDT (New York)",
                    "UTC" to "UTC",
                    "Asia/Kolkata" to "IST (Kolkata)"
                )
                ExposedDropdownMenuBox(expanded = timezoneExpanded, onExpandedChange = { timezoneExpanded = it }) {
                    OutlinedTextField(
                        value = timezones.find { it.first == selectedTimezone }?.second ?: "",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Timezone") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timezoneExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = timezoneExpanded, onDismissRequest = { timezoneExpanded = false }) {
                        timezones.forEach { (tzId, display) ->
                            DropdownMenuItem(
                                text = { Text(display) },
                                onClick = { selectedTimezone = tzId; timezoneExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // Date/Time Picker (Trigger Time)
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Alarm Trigger Time", style = MaterialTheme.typography.titleMedium)
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

        // Schedule Button with Duplicate Protection
        Button(
            onClick = {
                val runSiteKey = selectedSiteKey
                val runMuseumSlug = selectedMuseumSlug
                val runDateTime = selectedDateTime
                val runTimezone = selectedTimezone
                val limitCount = occurrenceLimit.toIntOrNull() ?: 0
                
                when {
                    runMuseumSlug.isNullOrEmpty() -> {
                        feedbackMessage = "Please select a museum"; feedbackSuccess = false
                    }
                    selectedMode == "booking" && runPreferredDays.isEmpty() && runPreferredDates.isEmpty() -> {
                        feedbackMessage = "Booking Mode requires at least one Day or Date selection."; feedbackSuccess = false
                    }
                    selectedMode == "booking" && runPreferredDays.isNotEmpty() && runPreferredDates.isNotEmpty() -> {
                        val invalid = runPreferredDates.filter { iso ->
                            val d = LocalDate.parse(iso)
                            val dayStr = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                            dayStr !in runPreferredDays
                        }
                        if (invalid.isNotEmpty()) {
                            feedbackMessage = "Contradiction: Date ${invalid.first()} is not a ${runPreferredDays.joinToString("/")}"; feedbackSuccess = false
                        } else {
                            processScheduling(scope, context, config, configManager, runDateTime, runTimezone, runSiteKey, runMuseumSlug, selectedCredentialId, selectedMode, runPreferredDays, runPreferredDates, isRecurring, limitCount, stopDateMillis) { msg, success ->
                                feedbackMessage = msg; feedbackSuccess = success
                                if (success) selectedDateTime = null
                            }
                        }
                    }
                    runDateTime == null -> {
                        feedbackMessage = "Please select a trigger date and time"; feedbackSuccess = false
                    }
                    runDateTime <= System.currentTimeMillis() -> {
                        feedbackMessage = "Please select a future trigger time"; feedbackSuccess = false
                    }
                    else -> {
                        processScheduling(scope, context, config, configManager, runDateTime, runTimezone, runSiteKey, runMuseumSlug, selectedCredentialId, selectedMode, runPreferredDays, runPreferredDates, isRecurring, limitCount, stopDateMillis) { msg, success ->
                            feedbackMessage = msg; feedbackSuccess = success
                            if (success) selectedDateTime = null
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Schedule Run") }

        // Feedback message
        if (feedbackMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (feedbackSuccess) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    feedbackMessage!!,
                    color = if (feedbackSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // --- Smart Grouped List (Headers) ---
        Text("Active Schedules", style = MaterialTheme.typography.titleLarge)

        config?.scheduledRuns?.let { runs ->
            if (runs.isEmpty()) {
                Text("No scheduled runs", style = MaterialTheme.typography.bodyMedium)
            } else {
                val sortedRuns = runs.sortedBy { it.dropTimeMillis }
                val now = LocalDate.now()
                val tomorrow = now.plusDays(1)

                val todayRuns = sortedRuns.filter { 
                    Instant.ofEpochMilli(it.dropTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate() == now 
                }
                val tomorrowRuns = sortedRuns.filter { 
                    Instant.ofEpochMilli(it.dropTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate() == tomorrow 
                }
                val laterRuns = sortedRuns.filter { 
                    Instant.ofEpochMilli(it.dropTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate() > tomorrow 
                }

                if (todayRuns.isNotEmpty()) {
                    ScheduleHeader("Triggering Today")
                    todayRuns.forEach { run -> RunListItem(run, config, configManager, scope, context) }
                }
                if (tomorrowRuns.isNotEmpty()) {
                    ScheduleHeader("Triggering Tomorrow")
                    tomorrowRuns.forEach { run -> RunListItem(run, config, configManager, scope, context) }
                }
                if (laterRuns.isNotEmpty()) {
                    ScheduleHeader("Upcoming")
                    laterRuns.forEach { run -> RunListItem(run, config, configManager, scope, context) }
                }
            }
        } ?: Text("Loading...")
    }
}

@Composable
private fun ScheduleHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun RunListItem(
    run: ScheduledRun, 
    config: AppConfig?, 
    configManager: ConfigManager, 
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    RunItem(run = run, config = config, onDelete = { showDeleteDialog = true })
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Scheduled Run") },
            text = {
                val museumName = config?.admin?.sites?.get(run.siteKey)?.museums?.get(run.museumSlug)?.name ?: run.museumSlug
                Text("Delete scheduled run for $museumName?")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        configManager.removeScheduledRun(run.id)
                        AlarmScheduler(context).cancelRun(run.id)
                    }
                    showDeleteDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun processScheduling(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    config: AppConfig?,
    configManager: ConfigManager,
    runDateTime: Long?,
    runTimezone: String,
    runSiteKey: String,
    runMuseumSlug: String?,
    selectedCredentialId: String?,
    selectedMode: String,
    runPreferredDays: Set<String>,
    runPreferredDates: Set<String>,
    isRecurring: Boolean,
    limit: Int,
    stopDate: Long?,
    onResult: (String, Boolean) -> Unit
) {
    if (runDateTime == null || runMuseumSlug == null) return
    scope.launch {
        try {
            val utcMillis = convertToUtcMillis(runDateTime, runTimezone)

            // v1.4 Feature 3: Atomic Duplicate Run Protection
            val isDuplicate = config?.scheduledRuns?.any {
                it.siteKey == runSiteKey &&
                it.museumSlug == runMuseumSlug &&
                it.dropTimeMillis == utcMillis
            } ?: false

            if (isDuplicate) {
                onResult("Conflict: This museum is already scheduled for this exact time.", false)
                return@launch
            }

            val run = ScheduledRun(
                id = UUID.randomUUID().toString(),
                siteKey = runSiteKey,
                museumSlug = runMuseumSlug,
                credentialId = selectedCredentialId,
                dropTimeMillis = utcMillis,
                mode = selectedMode,
                preferredDays = runPreferredDays.toList(),
                preferredDates = runPreferredDates.toList(),
                timezone = runTimezone,
                isRecurring = isRecurring,
                remainingOccurrences = if (isRecurring) limit else 0,
                endDateMillis = stopDate
            )
            configManager.addScheduledRun(run)
            val offsetMillis = AlarmScheduler.parseDurationToMillis(config?.general?.preWarmOffset ?: "30s")
            AlarmScheduler(context).scheduleRun(run, offsetMillis)
            onResult("Run scheduled successfully!", true)
        } catch (e: Exception) {
            onResult(e.message ?: "Failed to schedule", false)
            LogManager.addLog("ERROR", "Schedule failed: ${e.message}")
        }
    }
}

private fun convertToUtcMillis(localDateTimeMillis: Long, timezoneId: String): Long {
    val deviceCalendar = Calendar.getInstance().apply {
        timeInMillis = localDateTimeMillis
    }
    val localDateTime = LocalDateTime.of(
        deviceCalendar.get(Calendar.YEAR),
        deviceCalendar.get(Calendar.MONTH) + 1,  
        deviceCalendar.get(Calendar.DAY_OF_MONTH),
        deviceCalendar.get(Calendar.HOUR_OF_DAY),
        deviceCalendar.get(Calendar.MINUTE),
        0
    )
    return localDateTime.atZone(ZoneId.of(timezoneId)).toInstant().toEpochMilli()
}

@Composable
private fun RunItem(run: ScheduledRun, config: AppConfig?, onDelete: () -> Unit) {
    val site = config?.admin?.sites?.get(run.siteKey)
    val museum = site?.museums?.get(run.museumSlug)
    val credential = run.credentialId?.let { id -> site?.credentials?.find { it.id == id } }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(museum?.name ?: run.museumSlug, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${run.siteKey.uppercase()} • ${run.mode.uppercase()}${if (run.isRecurring) " • Daily Loop" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                        timeZone = TimeZone.getTimeZone(run.timezone)
                    }.format(Date(run.dropTimeMillis)),
                    style = MaterialTheme.typography.bodySmall
                )
                if (run.preferredDays.isNotEmpty() || run.preferredDates.isNotEmpty()) {
                    Text(
                        "Pref: ${run.preferredDays.joinToString(", ")} ${run.preferredDates.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                credential?.let {
                    Text(
                        "Card: ${it.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                if (run.isRecurring && run.remainingOccurrences > 0) {
                    Text(
                        "Cycles left: ${run.remainingOccurrences}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}
