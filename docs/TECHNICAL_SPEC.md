
***

# Android Wrapper for Go Booking Agent - Technical Specification

> **Note:** This document is the single source of truth for the Android wrapper application. It incorporates all previous enhancements, audits, and clarifications. It is written to be unambiguous and complete so that an LLM (e.g., Qwen Coder) can implement the entire app without further human guidance.

---

## 1. Overview & Goals

**Purpose:** Build a native Android app that controls the existing Go‑based appointment booking agent (`github.com/kiskey/apptcheck`). The app replaces the web dashboard, centralizes configuration, and uses Android’s native scheduling to trigger the agent at exact times.

**Key Features:**
* **Unified configuration** (General + Site‑specific) stored in a single DataStore.
* **Real‑time UI updates:** any configuration change instantly reflects across all screens.
* **PIN‑protected admin area** (hardcoded PIN `1234` for MVP).
* **Bulk import of museums** (text format `name:slug:museumid`).
* **Multiple credential sets per site** (library card + PIN + email) with default selection.
* **Scheduling of future runs** using `AlarmManager.setExactAndAllowWhileIdle`.
* **Foreground service** to run the Go agent during the strike window.
* **Live logs** with export functionality.
* Works on **Android 8.0+ (API 26)** and supports `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
* **UI dropdowns** display museum names; internal mappings use slugs and museumIds.

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
│  └── fun buildAgentConfig(run, config): String              │
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
2. `ConfigManager` updates DataStore → `configFlow` emits new `AppConfig`.
3. All ViewModels receive the new config → UI recomposes with fresh data.
4. *No manual refresh logic anywhere.*

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
> **Important:** For KCLS, the `museumId` is often the same as the `slug`. The admin must provide both fields; the UI will show the name to the user.

### 3.3 CredentialSet
```kotlin
@Serializable
data class CredentialSet(
    val id: String = UUID.randomUUID().toString(),
    var label: String,     // user‑friendly name, e.g., "Main Card"
    var username: String,  // library card number
    var password: String,  // PIN
    var email: String
)
```

### 3.4 SiteConfig
```kotlin
@Serializable
data class SiteConfig(
    val name: String,                     // display name, e.g., "SPL"
    var baseUrl: String,                  // e.g., "https://spl.libcal.com"
    var availabilityEndpoint: String,     // "/pass/availability/institution"
    var digital: Boolean = true,
    var physical: Boolean = false,
    var location: String = "0",
    val museums: MutableMap<String, Museum> = mutableMapOf(),   // key = slug
    val credentials: MutableList<CredentialSet> = mutableListOf(),
    var defaultCredentialId: String? = null                     // must be an id in credentials
)
```
> **Validation Rule:** `defaultCredentialId` must be `null` or exist in credentials. When a credential is deleted, if it was default, set `defaultCredentialId = null`.

### 3.5 AdminConfig
```kotlin
@Serializable
data class AdminConfig(
    var activeSite: String = "spl",   // key in sites map
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
    var mode: String = "alert",                         // "alert" or "booking"
    var strikeTime: String = "09:00",                   // HH:MM
    var preferredDays: List<String> = listOf("Monday", "Wednesday", "Friday"),
    var ntfyTopic: String = "myappointments",
    var preferredMuseumSlug: String = "",               // stores the slug of the chosen museum
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
> **Explanation:** The user selects a museum by its name in the UI, but we store its slug in `preferredMuseumSlug`. This slug is used to look up the museum object when needed.

### 3.7 ScheduledRun
```kotlin
@Serializable
data class ScheduledRun(
    val id: String = UUID.randomUUID().toString(),
    val siteKey: String,                  // key in admin.sites
    val museumSlug: String,               // slug in that site's museums
    val credentialId: String?,            // null = use site's default
    val dropTimeMillis: Long,
    val mode: String                      // "alert" or "booking"
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
> **Important:** The `scheduledRuns` list is stored in the same JSON. When a run finishes (success or timeout), it should be removed from the list (so that it no longer appears in the schedule screen and no alarm remains). The removal is done by the same `ConfigManager.update` mechanism.

---

## 4. Central Configuration Manager

### 4.1 Storage
Use `androidx.datastore.preferences.core.Preferences` with a single key `"app_config"` that holds the JSON string of `AppConfig`.

**Dependencies:**
* `androidx.datastore:datastore-preferences:1.0.0`
* `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0`

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

    companion object {
        private val CONFIG_KEY = stringPreferencesKey("app_config")
    }
}

// Extension functions (in same file)
fun Preferences.toAppConfig(): AppConfig {
    val json = this[CONFIG_KEY] ?: return AppConfig()
    return Json.decodeFromString(json)
}

fun Preferences.withConfig(config: AppConfig): Preferences {
    val json = Json.encodeToString(config)
    return toMutablePreferences().apply { this[CONFIG_KEY] = json }.toPreferences()
}
```

### 4.3 JSON Builder for Go Agent
This function creates the exact JSON expected by `mobile/agent.go`. It must use exact field names (case‑sensitive) as defined in the Go struct.

```kotlin
fun buildAgentConfig(run: ScheduledRun, config: AppConfig): String {
    val site = config.admin.sites[run.siteKey] ?: error("Site not found")
    val museum = site.museums[run.museumSlug] ?: error("Museum not found")
    // Determine credential
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
                "preferredslug" to config.general.preferredMuseumSlug   // slug, not name
            )
        )
    )
    val request = mapOf(
        "siteKey" to run.siteKey,
        "museumSlug" to run.museumSlug,
        "dropTime" to java.time.Instant.ofEpochMilli(run.dropTimeMillis).toString(),
        "mode" to run.mode,
        "timezone" to java.util.TimeZone.getDefault().id,
        "fullConfig" to fullConfig
    )
    return Json { encodeDefaults = true }.encodeToString(request)
}
```
> **Note:** The `preferredslug` field in the JSON is the slug, not the name. The Go agent uses this to match against the museum’s slug.

---

## 5. UI Screens (Jetpack Compose)

All screens use `androidx.compose.material3` stable components. The app uses a `BottomNavigation` with four items: **Dashboard**, **Config**, **Schedule**, **Logs**.

### 5.1 DashboardScreen
**State:**
* `val config by configManager.configFlow.collectAsState()`
* `val isRunning by bookingViewModel.isRunning.collectAsState()` (from `BookingForegroundService` via `StateFlow`)

**Content:**
* **Status Card:** `Status: ${if (isRunning) "Running" else "Idle"}`
* **Next Run Countdown:** Computed from `config.scheduledRuns` sorted by `dropTimeMillis`. Show time remaining until the nearest run (or "No scheduled runs").
* **Start Now Button:** Creates a run with `dropTimeMillis = System.currentTimeMillis() + 30_000`, using:
  * `siteKey` = `config.admin.activeSite`
  * `museumSlug` = `config.general.preferredMuseumSlug` (if empty, use first museum in active site’s museums)
  * `credentialId` = `config.admin.sites[activeSite].defaultCredentialId`
  * `mode` = `config.general.mode`
  * Adds run via `configManager.addScheduledRun` and calls `AlarmScheduler.scheduleRun`.
* **Stop Button:** Calls `BookingForegroundService.stop(context)`. If a run is active, also stops the service.
* **Quick Stats:** Show:
  * Active Site: `config.admin.activeSite`
  * Mode: `config.general.mode`
  * Preferred Museum: Look up the museum by `config.general.preferredMuseumSlug` from the active site’s museums, and display its name. If not found, show "None".

### 5.2 ConfigScreen (PIN‑protected)
**PIN Dialog:**
* `AlertDialog` with `TextField` for PIN, hardcoded `"1234"`. On success, show the main config UI.

**Main UI:**
* `TabRow` with tabs **General** and **Sites**.

#### 5.2.1 General Tab
* **Mode:** `SegmentedButton` with options "Alert", "Booking".
* **Strike Time:** `OutlinedTextField` with click handler to open `TimePicker`. Store as `HH:MM` string.
* **Preferred Days:** `FlowRow` of `FilterChip` for each day of week. `selectedDays` is a list of day names.
* **ntfy Topic:** `OutlinedTextField`.
* **Preferred Museum Dropdown:**
  * Uses `ExposedDropdownMenuBox` with items built from `config.admin.sites[config.admin.activeSite].museums.values`.
  * Each item displays the museum’s name.
  * On selection, we store the corresponding slug into `general.preferredMuseumSlug`.
  * The dropdown’s selected item is determined by finding the museum whose slug matches `preferredMuseumSlug`.

**Implementation snippet:**
```kotlin
val museums = config.admin.sites[activeSite].museums.values.toList()
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
* **Performance Tuning:** Expanded section containing `OutlinedTextField` for `checkWindow`, `checkInterval`, `requestJitter`, `monthsToCheck`, `preWarmOffset`, `maxWorkers`, `restCycleChecks`, `restCycleDuration`. Use appropriate `KeyboardType`.
* **Save Button:** `onClick -> configManager.updateGeneral(newGeneral)`.

#### 5.2.2 Sites Tab
* **Active Site Dropdown:** `ExposedDropdownMenuBox` from `config.admin.sites.keys`. Selecting updates `selectedSiteKey`.
* **Site‑specific fields** (for selected site):
  * Base URL, Availability Endpoint – `TextField`.
  * Digital, Physical – `Checkbox`.
  * Location – `TextField`.
* **Museums Section:**
  * `LazyColumn` of `Museum` items, each showing `name` (`slug`, `museumId`) with `IconButton` (edit, delete).
  * `FloatingActionButton` (add) – opens dialog with `name`, `slug`, `museumId`. On confirm, adds to site’s museums map (key = slug). If slug already exists, ask to overwrite.
  * `IconButton` (bulk import) – opens dialog with large `TextField` for pasting lines `name:slug:museumid`. Parse lines, show preview `LazyColumn`, and an `Import` button that adds all valid entries (replacing duplicates by slug).
* **Credentials Section:**
  * `LazyColumn` of `CredentialSet` cards. Each card shows `label`, `username`, `password` (obscured), `email`, and buttons: Edit, Delete, and Star to mark as default.
  * `FloatingActionButton` (add credential) – opens dialog with label, username, password, email.
* **Save Button:** Collects all changes and calls `configManager.updateAdmin(updatedAdmin)`.

> **Validation:** Ensure default credential exists; if default credential is deleted, clear it. Also ensure preferred museum slug in General tab is still valid after site changes (handled in `updateAdmin`).

### 5.3 ScheduleScreen
**State:**
* `val config by configManager.configFlow.collectAsState()`
* `var selectedSite by remember { mutableStateOf(config.admin.activeSite) }`
* `var selectedMuseumSlug by remember { mutableStateOf("") }`
* `var selectedCredentialId by remember { mutableStateOf<String?>(null) }`
* `var selectedMode by remember { mutableStateOf(config.general.mode) }`
* `var selectedDateTime by remember { mutableStateOf<Long?>(null) }`

**UI:**
* **Site Dropdown:** `ExposedDropdownMenuBox` from `config.admin.sites.keys`. When changed, reset museum and credential selections.
* **Museum Dropdown:**
  * Built from the selected site’s `museums.values`.
  * Displays museum name.
  * Selected value stored as `selectedMuseumSlug` (slug).
  * If the current `selectedMuseumSlug` is not in the new site’s museums, clear it.
* **Credential Dropdown:**
  * Built from the selected site’s `credentials`.
  * Add an extra option "Use default" (`credentialId = null`).
  * Pre‑select the site’s `defaultCredentialId` if it exists, otherwise "Use default".
* **Mode Dropdown:** "Alert" / "Booking". Pre‑selected from `selectedMode`.
* **Date/Time Picker:** Use `DatePickerDialog` + `TimePickerDialog` (or a combined library) to select a future timestamp. Store as `Long` (milliseconds). Show selected date/time in a `TextField` with `read‑only`.
* **Schedule Button:** Creates a `ScheduledRun` with current selections and calls `configManager.addScheduledRun(run)` and `AlarmScheduler.scheduleRun(run)`.
* **Scheduled Runs List:** `LazyColumn` of `config.scheduledRuns` sorted by `dropTimeMillis`. Each item shows site, museum, mode, formatted date, and a delete icon. Deleting calls `configManager.removeScheduledRun(id)` and `AlarmScheduler.cancelRun(id)`.

### 5.4 LogsScreen
* **Live Logs:** `LazyColumn` observing `LogManager.logFlow`. Each item shows timestamp, level, message.
* **Auto‑scroll Toggle:** Switch to enable auto‑scroll to bottom when new logs arrive.
* **Export Button:** Opens share sheet with `LogManager.exportLogs(context)` URI.
* **Clear Button:** Calls `LogManager.clearInMemory()`.

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
            mode = intent.getStringExtra("mode") ?: "alert"
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

* Holds a reference to `MobileAgent` from the AAR.
* Provides `StateFlow<Boolean>` for `isRunning`.
* In `onStartCommand`, it:
  1. Gets the `ScheduledRun` from the intent.
  2. Loads current `AppConfig` via `ConfigManager`.
  3. Builds JSON with `ConfigManager.buildAgentConfig(run, config)`.
  4. Sets up callbacks:
     * `mobileAgent.setLogCallback { json -> LogManager.addLog(...) }`
     * `mobileAgent.setStatusCallback { status -> updateNotification(status) }`
  5. Starts the agent with `mobileAgent.start(json)`.
  6. Updates the persistent notification with the current status.
* When the agent finishes (or on stop request), calls `mobileAgent.stop()` and `stopSelf()`.
* **Notification:** Use `NotificationCompat.Builder` with channel ID `"booking_service"`. Include a stop action that calls `stopRun()`.

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

---

## 8. Go Agent Integration

* **AAR location:** `android-app/libs/booking.aar` (built via `gomobile` from the `mobile` directory).
* **Build script:** `scripts/build-go.sh` (see section 12).
* **Dependency in `app/build.gradle.kts`:** `implementation(files("$rootDir/libs/booking.aar"))`.

The Go agent exposes `MobileAgent` class with methods: 
* `start(configJSON: String)`
* `stop()`
* `isRunning(): Boolean`
* `setLogCallback((String) -> Unit)`
* `setStatusCallback((String) -> Unit)`

**Log callback format:** The Go agent sends JSON strings like `{"timestamp":123456,"level":"INFO","message":"Pre‑warming..."}`. The app should parse them and call `LogManager.addLog(level, message)`.

---

## 9. Error Handling & Validation

* **Configuration validation:**
  * In `ConfigScreen`, before saving admin, check that `defaultCredentialId` exists in credentials; if not, set to `null`.
  * In `ScheduleScreen`, before creating a run, verify that the selected site and museum exist; if credential is selected, ensure it exists.
* **Go agent errors:** All errors are logged. The Dashboard can show a toast for critical failures (e.g., "Booking failed").
* **Network errors:** Logged; no special handling.
* **Alarm scheduling:** If `dropTimeMillis` is in the past, do not schedule; show toast.

---

## 10. Security

* **PIN:** Hardcoded `"1234"` in `ConfigScreen` dialog. For production, replace with user‑set PIN stored in `EncryptedSharedPreferences`.
* **Credentials:** Stored in DataStore as plain text. For production, encrypt using `EncryptedSharedPreferences` (but keep the same data model).

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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.booking.bot"
        minSdk = 26
        targetSdk = 34
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
```

---

## 12. Build Script for Go AAR

`scripts/build-go.sh`:
```bash
#!/bin/bash
# Build Go AAR for Android wrapper
# Following TECHNICAL_SPEC.md section 12

set -e

echo "Building Go AAR for Android..."

# Check if mobile directory exists (Go agent code is in workspace root 'mobile' package)
if [ ! -d "mobile" ]; then
    echo "Error: mobile directory not found"
    exit 1
fi

# Download dependencies from workspace root
go mod download

# Initialize gomobile
gomobile init

# Create libs directory in android-app
mkdir -p android-app/libs

# Build the AAR - output to android-app/libs as per TECHNICAL_SPEC.md section 8
gomobile bind -target=android -o android-app/libs/booking.aar -androidapi 23 ./mobile

echo "Build complete! AAR file created at android-app/libs/booking.aar"
```
> **Note:** Make it executable via `chmod +x scripts/build-go.sh`. Run before building the Android app. The script builds the AAR from the `mobile` package at the workspace root and outputs it to `android-app/libs/booking.aar` where Gradle expects it (`$rootDir/libs/booking.aar`).

---

## 13. Testing & Acceptance Criteria

* [ ] App builds without errors on all supported ABIs.
* [ ] App runs on Android 8.0 (API 26) and Android 14 (API 34) emulators/devices.
* [ ] PIN `1234` grants access to `ConfigScreen`.
* [ ] General settings save and persist; changes immediately reflect in Dashboard and Schedule.
* [ ] Admin can add/edit/delete museums; bulk import works (parses lines, shows preview, imports).
* [ ] Admin can add/edit/delete credential sets; marking default works.
* [ ] Schedule screen shows correct museums (by name) and credentials for selected site; default credential preselected.
* [ ] `Start Now` creates a run with current settings; run triggers in 30 seconds; logs appear.
* [ ] Scheduled run triggers at exact time (within 1 second) and starts foreground service.
* [ ] Logs are displayed live; export works (shares file).
* [ ] After device reboot, scheduled runs are restored (alarms re‑registered).
* [ ] No experimental Compose APIs used; no compiler warnings about experimental APIs (suppressed via compiler flags).
* [ ] Release APK builds and uploads to GitHub releases via CI.

---

## 14. Appendix: Museum Name Display & Mapping Logic

* **Internal storage:** Museums are stored in `SiteConfig.museums` as a map `slug` -> `Museum`.
* **UI display:** Whenever a list of museums is shown, we use `museums.values` and display `museum.name`.
* **User selection:** The user sees names; the selected value is the corresponding `slug`.
* **GeneralSettings:** Stores `preferredMuseumSlug` (slug).
* **ScheduledRun:** Stores `museumSlug` (slug).
* **JSON to Go agent:** The `fullConfig` includes the museum’s `slug` and `museumid`. The `preferredslug` field is also the slug.
