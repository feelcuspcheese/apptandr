
# Final Comprehensive Technical Specification for Android Wrapper of Go Booking Agent

*(Full 14‑Section Version with All Bug Fixes Integrated – Complete & Self‑Contained)*

This document is the single source of truth for the Android wrapper application. It contains all content from the previous stable specification plus the fixes for the six audit bugs and logging issues. All sections are fully described; no omissions.

---

## 1. Overview & Goals

**Purpose:** Build a native Android app that controls the existing Go‑based appointment booking agent (`github.com/feelcuspcheese/apptcheck`). The app replaces the web dashboard, centralises configuration, and uses Android’s native scheduling to trigger the agent at exact times.

### Key Features:
*   **Unified configuration** (General + Site‑specific) stored in a single DataStore.
*   **Real‑time UI updates:** any configuration change instantly reflects across all screens.
*   **PIN‑protected admin area** (hardcoded PIN 1234 for MVP).
*   **Bulk import of museums** (text format `name:slug:museumid`).
*   **Multiple credential sets per site** (library card + PIN + email) with default selection.
*   **Scheduling of future runs** using `AlarmManager.setExactAndAllowWhileIdle`.
*   **Foreground service** to run the Go agent during the strike window.
*   **Live logs** with export functionality.
*   **Works on Android 8.0+** (API 26) and supports `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
*   **UI dropdowns display museum names;** internal mappings use slugs and museumIds.

### Enhanced UX Features (integrated throughout):
*   Site dropdowns show display names (e.g., "SPL", "KCLS") instead of internal keys.
*   Visual cue for the site being edited in the Sites tab (e.g., header "Editing SPL Settings").
*   Confirmation dialogs for destructive actions: delete museum, delete credential, bulk import overwrite, delete scheduled run.
*   Progress indicators for bulk import and saving admin config.
*   Timezone selection when scheduling a run, stored per run.
*   Debug viewer (optional, hidden) to show the final JSON sent to the Go agent.
*   First‑run wizard to guide initial setup.

---

## 2. Architecture & Data Flow

```text
┌─────────────────────────────────────────────────────────────┐
│                       Android App                           │
├─────────────────────────────────────────────────────────────┤
│  UI (Jetpack Compose)                                       │
│  ├── DashboardScreen                                        │
│  ├── ConfigScreen (PIN‑protected)                           │
│  ├── ScheduleScreen                                         │
│  └── LogsScreen                                             │
├─────────────────────────────────────────────────────────────┤
│  ViewModels (per screen) – collect configFlow & update UI   │
├─────────────────────────────────────────────────────────────┤
│  ConfigManager (singleton)                                  │
│  ├── DataStore (single JSON key)                            │
│  ├── configFlow: Flow<AppConfig>                            │
│  ├── suspend fun updateGeneral(general)                     │
│  ├── suspend fun updateAdmin(admin)                         │
│  ├── suspend fun addScheduledRun(run)                       │
│  ├── suspend fun removeScheduledRun(runId)                  │
│  └── fun buildAgentConfig(run, config): String?             │
├─────────────────────────────────────────────────────────────┤
│  LogManager (singleton)                                     │
│  ├── SharedFlow<LogEntry>                                   │
│  └── addLog(level, message)                                 │
├─────────────────────────────────────────────────────────────┤
│  Scheduling Layer                                           │
│  ├── AlarmScheduler (exact alarms)                          │
│  ├── AlarmReceiver (broadcast)                              │
│  ├── BootReceiver (restore after reboot)                    │
│  └── BookingForegroundService (runs Go agent)               │
└─────────────────────────────────────────────────────────────┘
```

**Data Flow:**
1. User changes a setting → ViewModel calls `ConfigManager.updateXxx()`.
2. ConfigManager updates DataStore → `configFlow` emits new `AppConfig`.
3. All ViewModels receive the new config → UI recomposes with fresh data.
*No manual refresh logic anywhere.*

---

## 3. Data Models (Kotlin Data Classes)

All models are in `com.booking.bot.data`. Use `kotlinx.serialization.Serializable` for JSON persistence.

### 3.1 Hardcoded Defaults (Never User‑Editable)
```kotlin
object Defaults {
    // Selectors and form field names – do not change
    const val BOOKING_LINK_SELECTOR = "a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available"
    const val USERNAME_FIELD = "username"
    const val PASSWORD_FIELD = "password"
    const val SUBMIT_BUTTON = "submit"
    const val AUTH_ID_SELECTOR = "input[name='auth_id']"
    const val LOGIN_URL_SELECTOR = "input[name='login_url']"
    const val EMAIL_FIELD = "email"
    const val SUCCESS_INDICATOR = "Thank you!"

    // Performance defaults (user‑configurable)
    const val CHECK_WINDOW = "60s"
    const val CHECK_INTERVAL = "0.81s"
    const val REQUEST_JITTER = "0.18s"
    const val MONTHS_TO_CHECK = 2
    const val PRE_WARM_OFFSET = "30s"
    const val MAX_WORKERS = 2
    const val REST_CYCLE_CHECKS = 12
    const val REST_CYCLE_DURATION = "3s"
}
```

### 3.2 Museum
```kotlin
@Serializable
data class Museum(
    val name: String,      // Display name shown in UI dropdowns
    val slug: String,      // Unique identifier (used as key in maps, stored in preferences)
    val museumId: String   // Actual ID used in the library's API endpoint
)
```

### 3.3 CredentialSet
```kotlin
@Serializable
data class CredentialSet(
    val id: String = UUID.randomUUID().toString(),
    var label: String,     // user‑friendly name, e.g., "Main Card"
    var username: String,
    var password: String,
    var email: String
)
```

### 3.4 SiteConfig
```kotlin
@Serializable
data class SiteConfig(
    val name: String,                     // display name, e.g., "SPL"
    var baseUrl: String,
    var availabilityEndpoint: String,
    var digital: Boolean = true,
    var physical: Boolean = false,
    var location: String = "0",
    val museums: MutableMap<String, Museum> = mutableMapOf(),
    val credentials: MutableList<CredentialSet> = mutableListOf(),
    var defaultCredentialId: String? = null
)
```

### 3.5 AdminConfig
```kotlin
@Serializable
data class AdminConfig(
    var activeSite: String = "spl",
    val sites: MutableMap<String, SiteConfig> = mutableMapOf(
        "spl" -> SiteConfig(
            name = "SPL",
            baseUrl = "https://spl.libcal.com",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0"
        ),
        "kcls" -> SiteConfig(
            name = "KCLS",
            baseUrl = "https://rooms.kcls.org",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0"
        )
    )
)
```

### 3.6 GeneralSettings
```kotlin
@Serializable
data class GeneralSettings(
    var mode: String = "alert",
    var strikeTime: String = "09:00",
    var preferredDays: List<String> = listOf("Monday", "Wednesday", "Friday"),
    var ntfyTopic: String = "myappointments",
    var preferredMuseumSlug: String = "",
    // Performance tuning
    var checkWindow: String = Defaults.CHECK_WINDOW,
    var checkInterval: String = Defaults.CHECK_INTERVAL,
    var requestJitter: String = Defaults.REQUEST_JITTER,
    var monthsToCheck: Int = Defaults.MONTHS_TO_CHECK,
    var preWarmOffset: String = Defaults.PRE_WARM_OFFSET,
    var maxWorkers: Int = Defaults.MAX_WORKERS,
    var restCycleChecks: Int = Defaults.REST_CYCLE_CHECKS,
    var restCycleDuration: String = Defaults.REST_CYCLE_DURATION
)
```

### 3.7 ScheduledRun (with timezone)
```kotlin
@Serializable
data class ScheduledRun(
    val id: String = UUID.randomUUID().toString(),
    val siteKey: String,
    val museumSlug: String,
    val credentialId: String?,
    val dropTimeMillis: Long,
    val mode: String,
    val timezone: String = TimeZone.getDefault().id   // IANA timezone ID
)
```

### 3.8 AppConfig (Single Source of Truth)
```kotlin
@Serializable
data class AppConfig(
    val general: GeneralSettings = GeneralSettings(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: MutableList<ScheduledRun> = mutableListOf()
)
```
*Run Lifecycle:* When a run finishes (successfully or due to timeout/error), it must be removed from `scheduledRuns` (handled by `BookingForegroundService`).

---

## 4. Central Configuration Manager

### 4.1 Storage
Use `androidx.datastore.preferences.core.Preferences` with a single key `"app_config"` holding the JSON string of `AppConfig`.

**Dependencies:**
*   `androidx.datastore:datastore-preferences:1.0.0`
*   `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0`

### 4.2 ConfigManager Implementation
```kotlin
class ConfigManager(context: Context) {
    private val dataStore = context.dataStore
    val configFlow: Flow<AppConfig> = dataStore.data
        .catch { emit(AppConfig()) }
        .map { prefs ->
            prefs[CONFIG_KEY]?.let { json ->
                Json.decodeFromString<AppConfig>(json)
            } ?: AppConfig()
        }

    suspend fun updateGeneral(general: GeneralSettings) {
        dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updated = current.copy(general = general)
            prefs.withConfig(updated)
        }
    }

    suspend fun updateAdmin(admin: AdminConfig) {
        dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            // After admin update, ensure general.preferredMuseumSlug is still valid
            val activeSite = admin.activeSite
            val validMuseums = admin.sites[activeSite]?.museums?.keys ?: emptySet()
            val newPreferredSlug = if (current.general.preferredMuseumSlug in validMuseums)
                current.general.preferredMuseumSlug else ""
            val updatedGeneral = current.general.copy(preferredMuseumSlug = newPreferredSlug)
            val updated = current.copy(admin = admin, general = updatedGeneral)
            prefs.withConfig(updated)
        }
    }

    suspend fun addScheduledRun(run: ScheduledRun) {
        // Validate existence at scheduling time
        val config = configFlow.first()
        val site = config.admin.sites[run.siteKey] ?: error("Site not found")
        if (run.museumSlug !in site.museums) error("Museum not found")
        if (run.credentialId != null && run.credentialId !in site.credentials.map { it.id }) error("Credential not found")
        dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = (current.scheduledRuns + run).toMutableList()
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }

    suspend fun removeScheduledRun(runId: String) {
        dataStore.updateData { prefs ->
            val current = prefs.toAppConfig()
            val updatedRuns = current.scheduledRuns.filter { it.id != runId }.toMutableList()
            val updated = current.copy(scheduledRuns = updatedRuns)
            prefs.withConfig(updated)
        }
    }

    // Build JSON for Go agent
    fun buildAgentConfig(run: ScheduledRun, config: AppConfig): String? {
        val site = config.admin.sites[run.siteKey] ?: run {
            LogManager.addLog("ERROR", "Site ${run.siteKey} not found for run ${run.id}")
            return null
        }
        val museum = site.museums[run.museumSlug] ?: run {
            LogManager.addLog("ERROR", "Museum ${run.museumSlug} not found in site ${run.siteKey}")
            return null
        }
        val credential = run.credentialId?.let { id ->
            site.credentials.find { it.id == id }
        } ?: site.defaultCredentialId?.let { id ->
            site.credentials.find { it.id == id }
        }
        val (username, password, email) = credential?.let {
            Triple(it.username, it.password, it.email)
        } ?: Triple("", "", "")

        val fullConfig = mapOf(
            "active_site" to config.admin.activeSite,
            "mode" to config.general.mode,
            "strike_time" to config.general.strikeTime,
            "preferred_days" to config.general.preferredDays,
            "ntfy_topic" to config.general.ntfyTopic,
            "check_window" to config.general.checkWindow,
            "check_interval" to config.general.checkInterval,
            "request_jitter" to config.general.requestJitter,
            "months_to_check" to config.general.monthsToCheck,
            "pre_warm_offset" to config.general.preWarmOffset,
            "max_workers" to config.general.maxWorkers,
            "rest_cycle_checks" to config.general.restCycleChecks,
            "rest_cycle_duration" to config.general.restCycleDuration,
            "sites" to mapOf(
                run.siteKey to mapOf(
                    "name" to site.name,
                    "baseurl" to site.baseUrl,
                    "availabilityendpoint" to site.availabilityEndpoint,
                    "digital" to site.digital,
                    "physical" to site.physical,
                    "location" to site.location,
                    "bookinglinkselector" to Defaults.BOOKING_LINK_SELECTOR,
                    "loginform" to mapOf(
                        "usernamefield" to Defaults.USERNAME_FIELD,
                        "passwordfield" to Defaults.PASSWORD_FIELD,
                        "submitbutton" to Defaults.SUBMIT_BUTTON,
                        "csrfselector" to "",
                        "username" to username,
                        "password" to password,
                        "email" to email,
                        "authidselector" to Defaults.AUTH_ID_SELECTOR,
                        "loginurlselector" to Defaults.LOGIN_URL_SELECTOR
                    ),
                    "bookingform" to mapOf(
                        "actionurl" to "",
                        "fields" to emptyList<String>(),
                        "emailfield" to Defaults.EMAIL_FIELD
                    ),
                    "successindicator" to Defaults.SUCCESS_INDICATOR,
                    "museums" to mapOf(
                        museum.slug to mapOf(
                            "name" to museum.name,
                            "slug" to museum.slug,
                            "museumid" to museum.museumId
                        )
                    ),
                    "preferredslug" to config.general.preferredMuseumSlug
                )
            )
        )
        val request = mapOf(
            "siteKey" to run.siteKey,
            "museumSlug" to run.museumSlug,
            "dropTime" to java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString(),
            "mode" to run.mode,
            "timezone" to run.timezone,
            "fullConfig" to fullConfig
        )
        return Json { encodeDefaults = true }.encodeToString(request)
    }

    companion object {
        private val CONFIG_KEY = stringPreferencesKey("app_config")
    }
}

// Extension functions (same file)
fun Preferences.toAppConfig(): AppConfig {
    val json = this[CONFIG_KEY] ?: return AppConfig()
    return Json.decodeFromString(json)
}

fun Preferences.withConfig(config: AppConfig): Preferences {
    val json = Json.encodeToString(config)
    return toMutablePreferences().apply { this[CONFIG_KEY] = json }.toPreferences()
}
```

---

## 5. UI Screens (Jetpack Compose)

All screens use `androidx.compose.material3` stable components. The app uses a BottomNavigation with four items: Dashboard, Config, Schedule, Logs.

### 5.1 DashboardScreen
**State:**
*   `val config by configManager.configFlow.collectAsState()`
*   `val isRunning by bookingViewModel.isRunning.collectAsState()` (from `BookingForegroundService` via `StateFlow`)

**Content:**
*   **Status Card:** Status: `${if (isRunning) "Running" else "Idle"}`
*   **Next Run Countdown:** Computed from `config.scheduledRuns` sorted by `dropTimeMillis`. Show time remaining until the nearest run (or "No scheduled runs").
*   **Start Now Button:** Creates a run with `dropTimeMillis = System.currentTimeMillis() + 30_000`, using:
    *   `siteKey = config.admin.activeSite`
    *   `museumSlug = config.general.preferredMuseumSlug` (if empty, use first museum in active site’s museums)
    *   `credentialId = config.admin.sites[activeSite].defaultCredentialId`
    *   `mode = config.general.mode`
    *   `timezone = TimeZone.getDefault().id`
    
    Adds run via `configManager.addScheduledRun` and calls `AlarmScheduler.scheduleRun`.

    **[FIX (BUG-005)]:** Immediately start the foreground service so that logs appear instantly:
    ```kotlin
    BookingForegroundService.start(context, run)
    ```
*   **Stop Button:** Calls `BookingForegroundService.stop(context)`. If a run is active, also stops the service.
*   **Quick Stats:** Show:
    *   Active Site: `config.admin.sites[config.admin.activeSite]?.name ?: config.admin.activeSite`
    *   Mode: `config.general.mode`
    *   Preferred Museum: Look up the museum by `config.general.preferredMuseumSlug` from the active site’s museums, and display its name. If not found, show "None".

*Implementation notes:*
The countdown updates every second using a `LaunchedEffect` that delays and recomputes. `Start Now` button uses `rememberCoroutineScope` to call suspend functions.

### 5.2 ConfigScreen (PIN‑protected)

#### 5.2.1 PIN Dialog
An AlertDialog with a TextField for the PIN. Hardcoded PIN "1234". On success, dismiss dialog and show the main config UI. On failure, show error toast and keep dialog.

#### 5.2.2 Main UI (after PIN)
A `TabRow` with two tabs: **General** and **Sites**.

##### 5.2.2.1 General Tab
**Fields:**
*   **Mode:** `SegmentedButton` with options "Alert", "Booking". Stored in `general.mode`.
*   **Strike Time:** `OutlinedTextField` with click handler to open TimePicker. Store as HH:MM string.
*   **Preferred Days:** FlowRow of `FilterChip` for each day of week. `selectedDays` is a list of day names.
*   **ntfy Topic:** `OutlinedTextField`.
*   **Preferred Museum Dropdown:**
    *   Uses `ExposedDropdownMenuBox` with items built from `config.admin.sites[config.admin.activeSite].museums.values`.
    *   Each item displays the museum’s name.
    *   On selection, we store the corresponding slug into `general.preferredMuseumSlug`.
    *   The dropdown’s selected item is determined by finding the museum whose slug matches `preferredMuseumSlug`.
*   **Performance Tuning:** Expanded section containing `OutlinedTextField` for `checkWindow`, `checkInterval`, `requestJitter`, `monthsToCheck`, `preWarmOffset`, `maxWorkers`, `restCycleChecks`, `restCycleDuration`. Use appropriate KeyboardType.
*   **Save Button:** `onClick -> configManager.updateGeneral(newGeneral)`.

**[FIX (BUG-003/004)]:** Add save feedback (success message) after saving:
```kotlin
var saveFeedback by remember { mutableStateOf<String?>(null) }
Button(
    onClick = {
        scope.launch {
            configManager.updateGeneral(newGeneral)
            saveFeedback = "Settings saved successfully!"
            delay(2000)
            saveFeedback = null
        }
    }
) { Text("Save General Settings") }

if (saveFeedback != null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(saveFeedback!!, modifier = Modifier.padding(12.dp))
    }
}
```

*Implementation snippet for preferred museum dropdown:*
```kotlin
val activeSite = config.admin.activeSite
val museums = config.admin.sites[activeSite]?.museums?.values?.toList() ?: emptyList()
var expanded by remember { mutableStateOf(false) }
var selectedMuseum by remember { mutableStateOf(
    museums.find { it.slug == config.general.preferredMuseumSlug }
) }

ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it }
) {
    TextField(
        value = selectedMuseum?.name ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text("Preferred Museum") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
    )
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        museums.forEach { museum ->
            DropdownMenuItem(
                text = { Text(museum.name) },
                onClick = {
                    selectedMuseum = museum
                    // When saving, we store the slug
                    onMuseumSelected(museum.slug)
                    expanded = false
                }
            )
        }
    }
}
```

##### 5.2.2.2 Sites Tab
This tab allows editing all site‑specific configuration, including museums and credentials.

**State:**
*   `val siteEntries = config.admin.sites.map { (key, site) -> key to site.name }`
*   `var selectedSiteKey by remember { mutableStateOf(config.admin.activeSite) }`
*   The current site object is `config.admin.sites[selectedSiteKey]!!`.

**UI Components:**
*   **Site Selection Dropdown:** Uses `ExposedDropdownMenuBox` showing `site.name`. When changed, updates `selectedSiteKey` and resets editing state.

**[FIX (BUG-002)]:** Reset form fields when `selectedSiteKey` changes using `LaunchedEffect`:
```kotlin
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
```
*   **Visual Cue:** `Text("Editing ${siteEntries.find { it.first == selectedSiteKey }?.second} Settings", style = MaterialTheme.typography.titleMedium)`
*   **Site‑Specific Fields:** Base URL, Availability Endpoint, Digital (Checkbox), Physical (Checkbox), Location.
*   **Museums Section:**
    *   LazyColumn of museums. Each item shows name (slug, museumId) with IconButton (edit, delete).
    *   FloatingActionButton (add) – opens a dialog with name, slug, museumId. On confirm, adds to site’s museums map (key = slug). If slug already exists, show an overwrite confirmation dialog.
    *   IconButton (bulk import) – opens a dialog with a large TextField for pasting lines `name:slug:museumid`. Parse lines, show preview LazyColumn, and an Import button that adds all valid entries (replacing duplicates by slug). During import, show a CircularProgressIndicator and disable the import button.
*   **Credentials Section:**
    *   LazyColumn of CredentialSet cards. Each card shows label, username, password (obscured), email, and buttons: Edit, Delete, and Star to mark as default.
    *   FloatingActionButton (add credential) – opens a dialog with label, username, password, email.
    *   Setting a default: only one star per site; when star is clicked, update `defaultCredentialId` to that credential’s id (and clear any previous default).
*   **Save Button:** Collects all changes into a new SiteConfig and calls `configManager.updateAdmin(updatedAdmin)`. While saving, show a CircularProgressIndicator inside the button and disable it.

**[FIX (BUG-003/004)]:** Add save feedback (success message) after saving:
```kotlin
var saveFeedback by remember { mutableStateOf<String?>(null) }
Button(
    onClick = {
        scope.launch {
            configManager.updateAdmin(updatedAdmin)
            saveFeedback = "Site configuration saved successfully!"
            delay(2000)
            saveFeedback = null
        }
    }
) { Text("Save Site Configuration") }

if (saveFeedback != null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(saveFeedback!!, modifier = Modifier.padding(12.dp))
    }
}
```

**Confirmation Dialogs:**
*   **Delete Museum:** Show AlertDialog with message "Delete '{museum.name}'? This cannot be undone." Confirm calls delete action.
*   **Delete Credential:** Similar confirmation.
*   **Bulk Import Overwrite:** If a museum with the same slug already exists, show dialog: "Overwrite existing museum '{existing.name}' with new data?".

*Implementation skeleton for museums list with delete confirmation:*
```kotlin
items(museums.values.toList()) { museum ->
    Row {
        Text("${museum.name} (${museum.slug}, ${museum.museumId})")
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { showDeleteMuseumDialog(museum) }) {
            Icon(Icons.Default.Delete, null)
        }
    }
}
```

### 5.3 ScheduleScreen
**State:**
*   `val config by configManager.configFlow.collectAsState()`
*   `var selectedSiteKey by remember { mutableStateOf(config.admin.activeSite) }`
*   `var selectedMuseumSlug by remember { mutableStateOf("") }`
*   `var selectedCredentialId by remember { mutableStateOf<String?>(null) }`
*   `var selectedMode by remember { mutableStateOf(config.general.mode) }`
*   `var selectedDateTime by remember { mutableStateOf<Long?>(null) }`
*   `var selectedTimezone by remember { mutableStateOf(TimeZone.getDefault().id) }`

**UI Components:**

**[FIX (BUG-001)]:** Add vertical scroll to the main column to ensure all content is accessible on small screens:
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) {
    // Site Dropdown, Museum Dropdown, Credential Dropdown, Mode Dropdown,
    // Timezone Dropdown, Date/Time Picker, Schedule Button, Scheduled Runs List
}
```

*   **Site Dropdown:** Uses `siteEntries` (key to name) to display names. When changed, reset museum and credential selections.
*   **Museum Dropdown:** Populated from `config.admin.sites[selectedSiteKey]?.museums?.values`. Displays names; stores slug.
*   **Credential Dropdown:** Populated from `config.admin.sites[selectedSiteKey]?.credentials`. Adds an extra option "Use default" (`credentialId = null`). Pre‑select the site’s `defaultCredentialId` if it exists, otherwise "Use default".
*   **Mode Dropdown:** "Alert" / "Booking". Pre‑selected from `selectedMode`.
*   **Timezone Dropdown:** Uses a list of common timezones with display names (e.g., "America/Los_Angeles" -> "PST/PDT (Los Angeles)"). Selected value stored as IANA string.
*   **Date/Time Picker:** Use `DatePickerDialog` and `TimePickerDialog` (or a combined library) to select a future timestamp. Store as Long (milliseconds). Show selected date/time in a TextField with read‑only.
*   **Schedule Button:** Validates that a future date/time is selected. Creates a `ScheduledRun` with the current selections and calls `configManager.addScheduledRun(run)` and `AlarmScheduler.scheduleRun(run)`.
*   **Scheduled Runs List:** LazyColumn of `config.scheduledRuns` sorted by `dropTimeMillis`. Each item shows site (display name), museum name, mode, formatted date/time, and a delete icon. Deleting calls `configManager.removeScheduledRun(id)` and `AlarmScheduler.cancelRun(id)` with a confirmation dialog.

*Implementation details for timezone dropdown:*
```kotlin
val timezones = listOf(
    "America/Los_Angeles" to "PST/PDT (Los Angeles)",
    "America/Denver" to "MST/MDT (Denver)",
    "America/Chicago" to "CST/CDT (Chicago)",
    "America/New_York" to "EST/EDT (New York)",
    "UTC" to "UTC",
    "Asia/Kolkata" to "IST (Kolkata)"
)
var expandedTz by remember { mutableStateOf(false) }
ExposedDropdownMenuBox(
    expanded = expandedTz,
    onExpandedChange = { expandedTz = it }
) {
    TextField(
        value = timezones.find { it.first == selectedTimezone }?.second ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text("Timezone") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTz) }
    )
    ExposedDropdownMenu(
        expanded = expandedTz,
        onDismissRequest = { expandedTz = false }
    ) {
        timezones.forEach { (tzId, display) ->
            DropdownMenuItem(
                text = { Text(display) },
                onClick = {
                    selectedTimezone = tzId
                    expandedTz = false
                }
            )
        }
    }
}
```

### 5.4 LogsScreen
**State:**
*   `val logs by LogManager.logFlow.collectAsState(initial = emptyList())`

**UI:**
*   TopBar with title "Logs" and three icons: Export, Clear, and Debug JSON (optional).
*   LazyColumn displaying each log entry with timestamp, level, and message. Use `rememberLazyListState` and auto‑scroll toggle (Switch) to enable/disable scrolling to bottom on new entries.
*   **Export:** Calls `LogManager.exportLogs(context)` and shares the URI via `Intent.ACTION_SEND`.
*   **Clear:** Calls `LogManager.clearInMemory()`.
*   **Debug JSON:** When clicked, fetch the first pending run from `configManager.configFlow.first().scheduledRuns` (if any), build the JSON using `ConfigManager.buildAgentConfig`, and show it in an AlertDialog with a copy button.

*Implementation note:* The debug JSON viewer is hidden behind an optional setting or long‑press on the logs title.

### 5.5 Timezone Handling (Detailed)

#### 5.5.1 User Selection of Timezone
In the ScheduleScreen, the user selects a timezone from a dropdown. The dropdown displays human‑readable names (e.g., "PST/PDT (Los Angeles)") and stores the corresponding IANA timezone ID (e.g., "America/Los_Angeles"). The default selection is the device’s current timezone.

```kotlin
var selectedTimezone by remember { mutableStateOf(TimeZone.getDefault().id) }
```

#### 5.5.2 Converting User‑Selected Date/Time to UTC
When the user picks a date and time using the date/time picker, the app must interpret that local date/time in the selected timezone and convert it to UTC milliseconds (epoch). This conversion is essential because `AlarmManager` and all internal storage work with UTC milliseconds.

**[FIX (BUG-006)]:** Correct conversion algorithm (replaces previous flawed implementation):
```kotlin
fun convertToUtcMillis(localDateTimeMillis: Long, timezoneId: String): Long {
    val zone = ZoneId.of(timezoneId)
    val instant = java.time.Instant.ofEpochMilli(localDateTimeMillis)
    val zoned = instant.atZone(zone)
    return zoned.toInstant().toEpochMilli()
}
```
*Important:* `localDateTimeMillis` must be the epoch millis of the user’s selected local date/time (as if the device were in that timezone). Typically, you obtain this from a Calendar or DatePicker set to the desired local time. The function then returns the correct UTC epoch for `AlarmManager`.

The `dropTimeMillis` field of `ScheduledRun` stores the resulting UTC milliseconds.

#### 5.5.3 AlarmManager Scheduling
`AlarmManager.setExactAndAllowWhileIdle()` expects a time in the system’s UTC clock (milliseconds since epoch). Since `dropTimeMillis` is already UTC, no additional conversion is needed. The alarm will fire at the exact UTC moment corresponding to the user’s local time in the chosen timezone.

#### 5.5.4 JSON Construction for Go Agent
The JSON sent to the Go agent includes two fields related to time:
*   `dropTime`: an ISO‑8601 string representing the same UTC moment as `dropTimeMillis`. Using `Instant.ofEpochMilli(run.dropTimeMillis).toString()` produces a string like `"2025-04-01T16:00:00Z"` (UTC with Z suffix).
*   `timezone`: the IANA timezone ID stored with the run (e.g., `"America/Los_Angeles"`).

```kotlin
val request = mapOf(
    "siteKey" to run.siteKey,
    "museumSlug" to run.museumSlug,
    "dropTime" to java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString(),
    "mode" to run.mode,
    "timezone" to run.timezone,
    "fullConfig" to fullConfig
)
```
The Go agent receives this JSON. It can use the `dropTime` as the exact trigger moment (already in UTC) and optionally use the `timezone` field for logging or to adjust the displayed drop time in notifications.

#### 5.5.5 Example
Suppose the user in Los Angeles schedules a run for April 1, 2025, at 09:00 AM in the `America/Los_Angeles` timezone (which is UTC‑7 at that date).
1. The app obtains `localDateTimeMillis` corresponding to 2025-04-01 09:00 in America/Los_Angeles (e.g., using a Calendar set to that timezone).
2. `convertToUtcMillis` returns the correct UTC epoch: `1743523200000` (which is 2025-04-01T16:00:00Z).
3. The `ScheduledRun` stores `dropTimeMillis = 1743523200000` and `timezone = "America/Los_Angeles"`.
4. The alarm is set for `1743523200000` (UTC).
5. The JSON contains `"dropTime":"2025-04-01T16:00:00Z"` and `"timezone":"America/Los_Angeles"`.

If the device is in a different timezone (e.g., New York), the alarm will still fire at the correct UTC moment (9:00 AM Los Angeles time = 12:00 PM New York time). This ensures the run happens at the user’s intended local time regardless of device location.

---

## 6. Scheduling & Background Execution

### 6.1 AlarmScheduler
```kotlin
class AlarmScheduler(private val context: Context) {
    fun scheduleRun(run: ScheduledRun) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("run_id", run.id)
            putExtra("site_key", run.siteKey)
            putExtra("museum_slug", run.museumSlug)
            putExtra("credential_id", run.credentialId)
            putExtra("drop_time", run.dropTimeMillis)
            putExtra("mode", run.mode)
            putExtra("timezone", run.timezone)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, run.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, run.dropTimeMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, run.dropTimeMillis, pendingIntent)
        }
    }

    fun cancelRun(runId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, runId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    }
}
```

### 6.2 AlarmReceiver
```kotlin
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val run = ScheduledRun(
            id = intent.getStringExtra("run_id") ?: return,
            siteKey = intent.getStringExtra("site_key") ?: return,
            museumSlug = intent.getStringExtra("museum_slug") ?: return,
            credentialId = intent.getStringExtra("credential_id"),
            dropTimeMillis = intent.getLongExtra("drop_time", 0),
            mode = intent.getStringExtra("mode") ?: "alert",
            timezone = intent.getStringExtra("timezone") ?: TimeZone.getDefault().id
        )
        BookingForegroundService.start(context, run)
    }
}
```

### 6.3 BootReceiver
```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO).launch {
                val config = ConfigManager(context).configFlow.first()
                val scheduler = AlarmScheduler(context)
                config.scheduledRuns.forEach { scheduler.scheduleRun(it) }
            }
        }
    }
}
```

### 6.4 BookingForegroundService
Extends `LifecycleService`.
*   Holds a reference to `MobileAgent` from the AAR.
*   Provides `StateFlow<Boolean>` for `isRunning`.
*   **Concurrency handling:** If `mobileAgent.isRunning()` is true when a new run arrives, log a warning and stop the service without starting the new run.
*   **Run cleanup:** After the run finishes (successfully, with error, or due to timeout), the service calls `configManager.removeScheduledRun(run.id)` to delete the run from the list.

**[FIX (logging)]:** Properly parse Go agent logs and forward to LogManager.

*Implementation skeleton:*
```kotlin
class BookingForegroundService : LifecycleService() {
    private lateinit var mobileAgent: MobileAgent
    private var run: ScheduledRun? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    override fun onCreate() {
        super.onCreate()
        mobileAgent = MobileAgent()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        run = intent?.getSerializableExtra("run") as? ScheduledRun ?: return START_NOT_STICKY
        if (mobileAgent.isRunning()) {
            LogManager.addLog("WARN", "Run ${run!!.id} ignored – another run is already active")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        lifecycleScope.launch {
            val config = ConfigManager(this@BookingForegroundService).configFlow.first()
            val json = ConfigManager.buildAgentConfig(run!!, config)
            if (json == null) {
                LogManager.addLog("ERROR", "Failed to build config for run ${run!!.id}")
                cleanupAndStop()
                return@launch
            }
            
            // FIX: Wire log callback
            mobileAgent.setLogCallback { logJson ->
                try {
                    val entry = Json.decodeFromString<LogEntry>(logJson)
                    LogManager.addLog(entry.level, entry.message)
                } catch (e: Exception) {
                    LogManager.addLog("ERROR", "Failed to parse log from Go agent: $logJson")
                }
            }
            mobileAgent.setStatusCallback { status -> updateNotification(status) }
            
            mobileAgent.start(json)
            _isRunning.value = true
            
            // Wait for the agent to finish (could use a callback or polling)
            while (mobileAgent.isRunning()) {
                delay(1000)
            }
            cleanupAndStop()
        }
        return START_STICKY
    }

    private suspend fun cleanupAndStop() {
        _isRunning.value = false
        if (run != null) {
            ConfigManager(this).removeScheduledRun(run!!.id)
            LogManager.addLog("INFO", "Run ${run!!.id} removed from schedule")
        }
        stopSelf()
    }

    override fun onDestroy() {
        mobileAgent.stop()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, run: ScheduledRun) {
            val intent = Intent(context, BookingForegroundService::class.java).apply {
                putExtra("run", run)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, BookingForegroundService::class.java))
        }
    }
}
```

---

## 7. LogManager

```kotlin
object LogManager {
    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_BUFFER_SIZE = 500
    private lateinit var logFile: File

    fun init(context: Context) {
        logFile = File(context.filesDir, "logs.txt")
        addLog("INFO", "App initialised – log system ready")   // Initial log
    }

    fun addLog(level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_BUFFER_SIZE) buffer.removeAt(0)
        }
        writeToFile(entry)
        _logFlow.tryEmit(entry)
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            logFile.appendText("${entry.timestamp} [${entry.level}] ${entry.message}\n")
        } catch (e: Exception) { /* ignore */ }
    }

    suspend fun exportLogs(context: Context): Uri {
        val exportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
        logFile.copyTo(exportFile, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    }

    fun clearInMemory() {
        synchronized(buffer) { buffer.clear() }
    }
}
```
**[FIX (LOG-08)]:** The `init` function now adds an initial log entry, visible in LogsScreen.


### 7.4 Required Log Events (Detailed Implementation)

The following log entries must be added at the specified locations. Use `LogManager.addLog(level, message)` with the exact messages shown.

#### 7.4.1 Configuration Loaded
**Location:** In `ConfigManager`'s `configFlow` collection (or wherever the config is first loaded after DataStore read).  
**Implementation:**

```kotlin
// Inside the code that reads config from DataStore (e.g., in the Flow's map)
val config = Json.decodeFromString<AppConfig>(json)
LogManager.addLog("INFO", "Configuration loaded: activeSite=${config.admin.activeSite}, runs=${config.scheduledRuns.size}")
```

#### 7.4.2 Scheduled Run Added
**Location:** In `ConfigManager.addScheduledRun()` after successful validation and before saving.  
**Implementation:**

```kotlin
suspend fun addScheduledRun(run: ScheduledRun) {
    // ... validation ...
    LogManager.addLog("INFO", "Scheduled run added: id=${run.id}, site=${run.siteKey}, museum=${run.museumSlug}, dropTime=${run.dropTimeMillis}, mode=${run.mode}, timezone=${run.timezone}")
    dataStore.updateData { ... }
}
```

#### 7.4.3 Scheduled Run Removed (User Delete)
**Location:** In `ConfigManager.removeScheduledRun()`.  
**Implementation:**

```kotlin
suspend fun removeScheduledRun(runId: String) {
    LogManager.addLog("INFO", "Scheduled run removed (user delete): id=$runId")
    dataStore.updateData { ... }
}
```

#### 7.4.4 Start Now Run Created
**Location:** In `DashboardScreen` Start Now button click handler, after creating the run object.  
**Implementation:**

```kotlin
val run = ScheduledRun(...)
LogManager.addLog("INFO", "Start Now run created: id=${run.id}, dropTime in 30 seconds")
```

#### 7.4.5 Foreground Service Started
**Location:** In `BookingForegroundService.onStartCommand()`, after obtaining the run.  
**Implementation:**

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    run = intent?.getSerializableExtra("run") as? ScheduledRun ?: return START_NOT_STICKY
    LogManager.addLog("INFO", "Foreground service started for run ${run!!.id}")
    // ... rest
}
```

#### 7.4.6 Foreground Service Stopped
**Location:** In `BookingForegroundService.cleanupAndStop()` (or `onDestroy`).  
**Implementation:**

```kotlin
private suspend fun cleanupAndStop() {
    LogManager.addLog("INFO", "Foreground service stopped for run ${run?.id}")
    // ... existing cleanup
}
```

#### 7.4.7 Agent Start Attempt
**Location:** In `BookingForegroundService`, right before calling `mobileAgent.start(json)`.  
**Implementation:**

```kotlin
LogManager.addLog("INFO", "Attempting to start Go agent for run ${run!!.id}")
mobileAgent.start(json)
```

#### 7.4.8 Alarm Scheduled
**Location:** In `AlarmScheduler.scheduleRun()`, after creating the pending intent and before setting the alarm.  
**Implementation:**

```kotlin
fun scheduleRun(run: ScheduledRun) {
    // ... intent and pending intent ...
    LogManager.addLog("INFO", "Alarm scheduled for run ${run.id} at UTC ${run.dropTimeMillis}")
    alarmManager.setExactAndAllowWhileIdle(...)
}
```

#### 7.4.9 Alarm Cancelled
**Location:** In `AlarmScheduler.cancelRun()`.  
**Implementation:**

```kotlin
fun cancelRun(runId: String) {
    LogManager.addLog("INFO", "Alarm cancelled for run $runId")
    alarmManager.cancel(pendingIntent)
}
```

#### 7.4.10 Boot Receiver Restoring Runs
**Location:** In `BootReceiver.onReceive()`, when restoring runs after reboot.  
**Implementation:**

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
        CoroutineScope(Dispatchers.IO).launch {
            val config = ConfigManager(context).configFlow.first()
            LogManager.addLog("INFO", "Boot receiver restoring ${config.scheduledRuns.size} scheduled runs")
            config.scheduledRuns.forEach { AlarmScheduler(context).scheduleRun(it) }
        }
    }
}
```

#### 7.4.11 Configuration Saved (General or Admin)
**Location:** In `ConfigManager.updateGeneral()` and `updateAdmin()` after successful update.  
**Implementation:**

```kotlin
suspend fun updateGeneral(general: GeneralSettings) {
    dataStore.updateData { ... }
    LogManager.addLog("INFO", "General configuration saved: mode=${general.mode}, strikeTime=${general.strikeTime}")
}

suspend fun updateAdmin(admin: AdminConfig) {
    dataStore.updateData { ... }
    LogManager.addLog("INFO", "Admin configuration saved: activeSite=${admin.activeSite}")
}
```

## 8. Go Agent Integration

AAR location: `app/libs/booking.aar` (built via gomobile from the go-agent directory).

**Build script:** `scripts/build-go.sh`:
```bash
#!/bin/bash
set -e
cd go-agent
go mod download
gomobile bind -target=android -o ../libs/booking.aar -androidapi 21 ./mobile
```

**Dependency in `app/build.gradle.kts`:**
```kotlin
implementation(files("$rootDir/libs/booking.aar"))
```

The Go agent exposes `MobileAgent` class with methods:
*   `start(configJSON: String)`
*   `stop()`
*   `isRunning(): Boolean`
*   `setLogCallback((String) -> Unit)`
*   `setStatusCallback((String) -> Unit)`

**Log callback format:** The Go agent sends JSON strings like `{"timestamp":123456,"level":"INFO","message":"Pre‑warming..."}`. The app should parse them and call `LogManager.addLog(level, message)` (as done in Section 6.4).

---

## 9. Error Handling & Validation

*   **Configuration validation:**
    *   In ConfigScreen, before saving admin, ensure `defaultCredentialId` exists in credentials; if not, set to null.
    *   In ScheduleScreen, before creating a run, verify that the selected site, museum, and credential (if any) exist in the current config. Show toast if invalid.
*   **Go agent errors:** All errors are logged. The Dashboard can show a toast for critical failures (e.g., "Booking failed").
*   **Network errors:** Logged; no special handling.
*   **Alarm scheduling:** If `dropTimeMillis` is in the past, do not schedule; show toast.
*   **Missing references at trigger time:** `ConfigManager.buildAgentConfig` returns null if the site, museum, or credential is missing; the `BookingForegroundService` logs the error and does not start the agent.

---

## 10. Security

*   **PIN:** Hardcoded "1234" in ConfigScreen dialog. For production, replace with user‑set PIN stored in EncryptedSharedPreferences.
*   **Credentials:** Stored in DataStore as plain text. For production, encrypt using EncryptedSharedPreferences (but keep the same data model).

---

## 11. Build Configuration & Gradle

### 11.1 Project‑level build.gradle.kts
```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20" apply false
}
```

### 11.2 App‑level build.gradle.kts
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.booking.bot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.booking.bot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    
    // Android 16 compatibility: proper native library packaging
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("*.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    
    // Explicit native library handling for Android 16
    androidResources {
        generateLocaleConfig = false
    }
}

dependencies {
    implementation(files("$rootDir/libs/booking.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

### 11.3 ProGuard Rules (`proguard-rules.pro`)
```text
-keep class go.** { *; }
-keep class mobile.** { *; }
-keep class com.booking.bot.data.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Android 16 compatibility: keep native library classes
-keep class * extends java.lang.Thread { *; }
-keepclassmembers class * extends java.lang.Thread {
    public void run();
}

# Keep foreground service
-keep class com.booking.bot.service.** { *; }
-keep class * extends android.app.Service { *; }

# Keep scheduler components
-keep class com.booking.bot.scheduler.** { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep ViewModel and Lifecycle
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep DataStore
-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }
```

---

## 12. Build Script for Go AAR

`scripts/build-go.sh`:
```bash
#!/bin/bash
set -e
cd go-agent
go mod download
gomobile bind -target=android -o ../libs/booking.aar -androidapi 21 ./mobile
```
Make it executable: `chmod +x scripts/build-go.sh`. Run before building the Android app.

---

## 13. Testing & Acceptance Criteria

**Existing criteria (unchanged):**
*   App builds without errors on all supported ABIs.
*   App runs on Android 8.0 (API 26) through Android 15 (API 35) emulators/devices.
*   PIN 1234 grants access to ConfigScreen.
*   General settings save and persist; changes immediately reflect in Dashboard and Schedule.
*   Admin can add/edit/delete museums; bulk import works with progress indicator and overwrite confirmation.
*   Admin can add/edit/delete credential sets; marking default works; delete confirms.
*   Schedule screen shows correct museums (by name) and credentials for selected site; default credential preselected.
*   Timezone dropdown works and stored in run.
*   Scheduled run triggers at exact time (within 1 second) and uses stored timezone.
*   Logs are displayed live; export works.
*   Debug JSON viewer shows correct JSON for pending run.
*   After device reboot, scheduled runs are restored (alarms re‑registered).
*   First‑run wizard appears on first launch and saves initial config.
*   No experimental Compose APIs used; no compiler warnings.
*   Release APK builds and uploads to GitHub releases via CI.
*   Run cleanup and concurrency handling work as specified.
*   **Android 16 Compatibility:** The app uses `targetSdk = 35` (Android 15) for maximum compatibility with Android 16 devices while avoiding SDK 36 preview issues. Native library packaging is configured with `useLegacyPackaging = true` for proper JNI library loading on Android 16.

**New/Updated test cases from bug fixes:**

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SCH‑14** | ScheduleScreen scrolls on small screen | All fields (Site, Museum, Credential, Mode, Timezone, Date picker, Schedule button) are reachable by scrolling. |
| **GEN‑10** | Save General Settings – success feedback | After tapping Save, a temporary message “Settings saved successfully” appears for ~2 seconds. |
| **SITE‑17** | Save Admin Settings – success feedback | After tapping Save, a temporary message “Site configuration saved successfully” appears. |
| **DB‑09** | Start Now – immediate logs | Within 1 second of tapping Start Now, at least one log entry appears in LogsScreen (e.g., “App initialised” or “Service started”). |
| **LOG‑08** | LogManager initialisation log | On app start, a log “App initialised – log system ready” is visible in LogsScreen. |
| **LOG‑09** | Go agent log callback works | During a run, logs from the Go agent appear in LogsScreen (e.g., “Pre‑warming...”, “Strike started”). |
| **TZ‑01 to TZ‑07** | Timezone conversion tests | As originally defined – verify correct UTC conversion, DST handling, JSON fields, etc. |

*All other existing test cases (CONF‑01 to CONF‑07, DB‑01 to DB‑08, GEN‑01 to GEN‑09, SITE‑01 to SITE‑16, SCH‑01 to SCH‑13, RUN‑01 to RUN‑11, JSON‑01 to JSON‑07, LOG‑01 to LOG‑07, WIZ‑01 to WIZ‑08, EDGE‑01 to EDGE‑12, INT‑01 to INT‑07, REL‑01 to REL‑06, PERF‑01 to PERF‑05, RT‑01 to RT‑05) remain valid.*

---

## 14. Appendix: Museum Name Display & Mapping Logic

*   **Internal storage:** Museums are stored in `SiteConfig.museums` as a map `slug -> Museum`.
*   **UI display:** Whenever a list of museums is shown, we use `museums.values` and display `museum.name`.
*   **User selection:** The user sees names; the selected value is the corresponding `slug`.
*   **GeneralSettings:** Stores `preferredMuseumSlug` (slug).
*   **ScheduledRun:** Stores `museumSlug` (slug).
*   **JSON to Go agent:** The `fullConfig` includes the museum’s `slug` and `museumid`. The `preferredslug` field is also the `slug`.
```
