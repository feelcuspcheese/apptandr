Purpose: Detailed technical decisions, data models, and component interfaces.


# Technical Specification

## 1. Technology Stack

| Component            | Technology                         | Justification |
|----------------------|------------------------------------|---------------|
| Language             | Kotlin                             | Modern, coroutines, official |
| Minimum SDK          | 23 (Android 6.0)                   | Supports Android 6.0+ for broader device compatibility |
| Target SDK           | 34 (Android 14)                    | Latest stable |
| UI                   | Jetpack Compose                    | Declarative, fast development |
| Dependency Injection | Manual (no framework)              | Keep simple, avoid complexity |
| Persistence          | DataStore (Preferences)            | Type‑safe, async, simple |
| Scheduling           | AlarmManager (exact) + WorkManager | Exact timing for drop; WorkManager for cleanup |
| Background           | Foreground Service + BroadcastReceiver | Long‑running tasks |
| Logging              | Timber (optional) + custom file writer | Centralised, exportable |
| HTTP (in Go agent)   | Already implemented in Go          | No change needed |
| Go Integration       | Gomobile AAR (built from source)   | Reuse existing code |
| Supported Architectures | armeabi-v7a, arm64-v8a, x86, x86_64 | Universal APK supporting all major CPU architectures |

## 2. Data Models (Kotlin)

All config is stored in a single `AppConfig` data class. Hardcoded defaults are constants.

### 2.1 Protected Defaults (never exposed)

```kotlin
object Defaults {
    const val BOOKING_LINK_SELECTOR = "a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available"
    const val USERNAME_FIELD = "username"
    const val PASSWORD_FIELD = "password"
    const val SUBMIT_BUTTON = "submit"
    const val AUTH_ID_SELECTOR = "input[name='auth_id']"
    const val LOGIN_URL_SELECTOR = "input[name='login_url']"
    const val EMAIL_FIELD = "email"
    // Performance defaults (user‑facing)
    const val CHECK_WINDOW = "60s"
    const val CHECK_INTERVAL = "0.81s"
    const val REQUEST_JITTER = "0.18s"
    const val MONTHS_TO_CHECK = 2
    const val PRE_WARM_OFFSET = "30s"
    const val MAX_WORKERS = 2
    const val REST_CYCLE_CHECKS = 12
    const val REST_CYCLE_DURATION = "3s"
}


2.2 User Config (editable by user)

data class UserConfig(
    var mode: String = "alert",            // "alert" or "booking"
    var strikeTime: String = "09:00",      // HH:MM
    var preferredDays: List<String> = listOf("Monday", "Wednesday", "Friday"),
    var ntfyTopic: String = "myappointments",
    var preferredSlug: String = "",        // museum slug (string)
    // Performance tuning (advanced)
    var checkWindow: String = Defaults.CHECK_WINDOW,
    var checkInterval: String = Defaults.CHECK_INTERVAL,
    var requestJitter: String = Defaults.REQUEST_JITTER,
    var monthsToCheck: Int = Defaults.MONTHS_TO_CHECK,
    var preWarmOffset: String = Defaults.PRE_WARM_OFFSET,
    var maxWorkers: Int = Defaults.MAX_WORKERS,
    var restCycleChecks: Int = Defaults.REST_CYCLE_CHECKS,
    var restCycleDuration: String = Defaults.REST_CYCLE_DURATION
)

2.3 Admin Config (site‑specific, user credentials)

data class AdminConfig(
    var activeSite: String = "spl",      // "spl" or "kcls"
    val sites: MutableMap<String, SiteConfig> = mutableMapOf(
        "spl" -> SiteConfig(
            name = "SPL",
            baseUrl = "https://spl.libcal.com",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0",
            museums = mutableMapOf(
                "seattle-art-museum" -> Museum("Seattle Art Museum", "seattle-art-museum", "7f2ac5c414b2"),
                "zoo" -> Museum("Woodland Park Zoo", "zoo", "033bbf08993f")
            ),
            loginUsername = "",
            loginPassword = "",
            loginEmail = ""
        ),
        "kcls" -> SiteConfig(
            name = "KCLS",
            baseUrl = "https://rooms.kcls.org",
            availabilityEndpoint = "/pass/availability/institution",
            digital = true,
            physical = false,
            location = "0",
            museums = mutableMapOf(
                "kidsquest" -> Museum("KidsQuest Children's Museum", "kidsquest", "9ec25160a8a0")
            ),
            loginUsername = "",
            loginPassword = "",
            loginEmail = ""
        )
    )
)

data class SiteConfig(
    val name: String,
    var baseUrl: String,
    var availabilityEndpoint: String,
    var digital: Boolean,
    var physical: Boolean,
    var location: String,
    val museums: MutableMap<String, Museum>,   // slug -> Museum
    var loginUsername: String,
    var loginPassword: String,
    var loginEmail: String
)

data class Museum(
    val name: String,
    val slug: String,
    val museumId: String
)

2.4 Scheduled Run

data class ScheduledRun(
    val id: String = UUID.randomUUID().toString(),
    val siteKey: String,
    val museumSlug: String,
    val dropTimeMillis: Long,       // absolute time in milliseconds
    val mode: String                // "alert" or "booking"
)

2.5 Complete App Config (stored in DataStore)

data class AppConfig(
    val user: UserConfig = UserConfig(),
    val admin: AdminConfig = AdminConfig(),
    val scheduledRuns: MutableList<ScheduledRun> = mutableListOf()
)

3. Centralised Configuration Manager

class ConfigManager(private val context: Context) {
    private val dataStore = context.dataStore

    suspend fun loadConfig(): AppConfig {
        // Read from DataStore, fallback to defaults
    }

    suspend fun saveConfig(config: AppConfig) { ... }
    suspend fun updateUserConfig(user: UserConfig) { ... }
    suspend fun updateAdminConfig(admin: AdminConfig) { ... }
    suspend fun addScheduledRun(run: ScheduledRun) { ... }
    suspend fun removeScheduledRun(runId: String) { ... }

    // Build JSON for Go agent – merges user config, admin config, and protected defaults
    fun buildAgentConfig(run: ScheduledRun): String {
        val config = runBlocking { loadConfig() }
        val site = config.admin.sites[run.siteKey] ?: error("Site not found")
        val museum = site.museums[run.museumSlug] ?: error("Museum not found")

        // Assemble the full config structure expected by the Go agent.
        // This is identical to the JSON used in the web dashboard (fullConfig).
        return """
        {
          "siteKey": "${run.siteKey}",
          "museumSlug": "${run.museumSlug}",
          "dropTime": "${Instant.ofEpochMilli(run.dropTimeMillis)}",
          "mode": "${run.mode}",
          "timezone": "UTC",   // we'll use UTC; Android converts local time to UTC at schedule time
          "fullConfig": {
            "active_site": "${config.admin.activeSite}",
            "mode": "${config.user.mode}",
            "strike_time": "${config.user.strikeTime}",
            "preferred_days": ${jsonArray(config.user.preferredDays)},
            "ntfy_topic": "${config.user.ntfyTopic}",
            "check_window": "${config.user.checkWindow}",
            "check_interval": "${config.user.checkInterval}",
            "request_jitter": "${config.user.requestJitter}",
            "months_to_check": ${config.user.monthsToCheck},
            "pre_warm_offset": "${config.user.preWarmOffset}",
            "max_workers": ${config.user.maxWorkers},
            "rest_cycle_checks": ${config.user.restCycleChecks},
            "rest_cycle_duration": "${config.user.restCycleDuration}",
            "sites": {
              "${run.siteKey}": {
                "name": "${site.name}",
                "baseurl": "${site.baseUrl}",
                "availabilityendpoint": "${site.availabilityEndpoint}",
                "digital": ${site.digital},
                "physical": ${site.physical},
                "location": "${site.location}",
                "bookinglinkselector": "${Defaults.BOOKING_LINK_SELECTOR}",
                "loginform": {
                  "usernamefield": "${Defaults.USERNAME_FIELD}",
                  "passwordfield": "${Defaults.PASSWORD_FIELD}",
                  "submitbutton": "${Defaults.SUBMIT_BUTTON}",
                  "csrfselector": "",
                  "username": "${site.loginUsername}",
                  "password": "${site.loginPassword}",
                  "email": "${site.loginEmail}",
                  "authidselector": "${Defaults.AUTH_ID_SELECTOR}",
                  "loginurlselector": "${Defaults.LOGIN_URL_SELECTOR}"
                },
                "bookingform": {
                  "actionurl": "",
                  "fields": [],
                  "emailfield": "${Defaults.EMAIL_FIELD}"
                },
                "successindicator": "Thank you!",
                "museums": {
                  "${museum.slug}": {
                    "name": "${museum.name}",
                    "slug": "${museum.slug}",
                    "museumid": "${museum.museumId}"
                  }
                },
                "preferredslug": "${config.user.preferredSlug}"
              }
            }
          }
        }
        """.trimIndent()
    }
}

4. Centralised Logging Manager

object LogManager {
    private val _logFlow = MutableSharedFlow<LogEntry>()
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_BUFFER_SIZE = 500
    private lateinit var logFile: File

    fun init(context: Context) {
        logFile = File(context.filesDir, "logs.txt")
        // Load existing logs if needed
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
        // Create a copy of the log file and return a shareable URI
        val exportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
        logFile.copyTo(exportFile, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    }

    fun clearInMemory() {
        synchronized(buffer) { buffer.clear() }
    }
}

5. Go Agent Integration (via AAR)

The Go agent is compiled into an AAR with a MobileAgent class that provides:

// Methods exposed by the AAR
class MobileAgent {
    fun setLogCallback(callback: (String) -> Unit)
    fun setStatusCallback(callback: (String) -> Unit)
    fun start(configJSON: String): Boolean
    fun stop()
    fun isRunning(): Boolean
}

In the Android app, a wrapper service holds an instance of MobileAgent and forwards logs to LogManager


6. Scheduling with AlarmManager

class AlarmScheduler(private val context: Context) {
    fun scheduleRun(run: ScheduledRun) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("run_id", run.id)
            putExtra("site_key", run.siteKey)
            putExtra("museum_slug", run.museumSlug)
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


7. UI Specification (Compose)

7.1 Navigation
BottomNavigation with 5 items:

Dashboard
User Config
Admin Config (requires PIN)
Schedule
Logs
7.2 Dashboard Screen
Shows current run status (Idle / Running / Waiting)

Displays countdown to next scheduled run (if any)

Start Now button (creates run in 30 seconds)

Stop button (cancels current run)

Quick stats: active site, mode, preferred museum

Live log preview (last 3 logs)

7.3 User Config Screen
Mode (Alert/Booking) – RadioGroup

Strike Time – TimePicker

Preferred Days – Multi‑select chips (Mon‑Sun)

ntfy Topic – TextField

Preferred Museum – Dropdown (from active site’s museums)

Performance Tuning – Collapsible section with sliders/text fields for check window, interval, jitter, months, etc.

Save button

7.4 Admin Config Screen (PIN‑protected)
PIN: 1234 (hardcoded for MVP, can be changed later)

Site selector (SPL/KCLS)

For selected site:

Base URL – TextField

Availability Endpoint – TextField

Digital – Checkbox

Physical – Checkbox

Location – TextField

Museums – List view with add/delete/edit (name, slug, museumId)

Login Username, Password, Email – TextFields

Save button

7.5 Schedule Screen
Site dropdown

Museum dropdown (updates when site changes)

Mode dropdown

Date & Time picker (must be future)

Schedule button

List of scheduled runs (future only) with delete icon

7.6 Logs Screen
Scrollable text area (auto‑scroll toggle)

Export button (shares log file)

Clear button (clears in‑memory logs)

8. Background Execution
BookingForegroundService extends LifecycleService.

It holds an instance of MobileAgent.

When started, it calls MobileAgent.start(configJSON).

It displays a persistent notification with current status.

When the run finishes or user stops, it calls MobileAgent.stop() and stops itself.

9. Error Handling & Reporting
All errors from Go agent are logged via LogManager.addLog("ERROR", ...).

UI displays a toast for critical errors (e.g., "Booking failed").

Export logs for debugging.

10. Testing Strategy
Unit tests: ConfigManager, LogManager, JSON building.

UI tests: Compose previews, manual testing on device.

Integration tests: Run a test booking on a known museum (use alert mode first) and verify logs.

Scheduling tests: Use adb to set time and verify alarm fires.

11. Release Process (GitHub Actions)
Workflow triggered on tag v*.

Steps:

Checkout code.
Set up JDK 17.
Set up Go (to build the AAR).
Install gomobile.
Build AAR: gomobile bind -target=android -androidapi 23 -o libs/booking.aar ./go-agent/mobile
Build APK: ./gradlew assembleRelease
Sign APK using secrets.
Create GitHub release with APK and AAR as assets.
12. Acceptance Criteria
App installs and runs on Android 6.0+ (API 23) across all major CPU architectures (armeabi-v7a, arm64-v8a, x86, x86_64).

User can configure all fields and save them persistently.

Admin can edit site‑specific settings (PIN protected).

User can schedule a run; alarm fires at exact time.

Go agent starts, logs appear in‑app, and notifications (ntfy) are sent.

Booking mode works with correct credentials (tested on staging).

Logs can be exported and shared.

App survives device reboot (scheduled runs restored).

GitHub release contains signed APK and AAR.
