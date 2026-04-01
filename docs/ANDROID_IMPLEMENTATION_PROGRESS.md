# Android App Implementation Progress

This document tracks progress against the TECHNICAL_SPEC.md checklist items.

## Section 3: Data Models (Kotlin Data Classes)

### 3.1 Hardcoded Defaults
- [ ] `Defaults` object with all constants (BOOKING_LINK_SELECTOR, form fields, performance defaults)
- [ ] Located in `com.booking.bot.data` package

### 3.2 Museum
- [ ] `Museum` data class with `name`, `slug`, `museumId`
- [ ] `@Serializable` annotation for JSON persistence

### 3.3 CredentialSet
- [ ] `CredentialSet` data class with `id`, `label`, `username`, `password`, `email`
- [ ] UUID-based ID generation
- [ ] `@Serializable` annotation

### 3.4 SiteConfig
- [ ] `SiteConfig` with `name`, `baseUrl`, `availabilityEndpoint`, `digital`, `physical`, `location`
- [ ] `museums: MutableMap<String, Museum>` (key = slug)
- [ ] `credentials: MutableList<CredentialSet>`
- [ ] `defaultCredentialId: String?` with validation rule
- [ ] `@Serializable` annotation

### 3.5 AdminConfig
- [ ] `AdminConfig` with `activeSite` and `sites` map
- [ ] Default sites: "spl" and "kcls" pre-configured
- [ ] `@Serializable` annotation

### 3.6 GeneralSettings
- [ ] `GeneralSettings` with `mode`, `strikeTime`, `preferredDays`, `ntfyTopic`, `preferredMuseumSlug`
- [ ] Performance tuning fields: `checkWindow`, `checkInterval`, `requestJitter`, etc.
- [ ] Uses `Defaults` for default values
- [ ] `@Serializable` annotation

### 3.7 ScheduledRun
- [ ] `ScheduledRun` with `id`, `siteKey`, `museumSlug`, `credentialId`, `dropTimeMillis`, `mode`
- [ ] UUID-based ID generation
- [ ] `@Serializable` annotation

### 3.8 AppConfig
- [ ] `AppConfig` with `general`, `admin`, `scheduledRuns`
- [ ] Single source of truth stored in DataStore
- [ ] `@Serializable` annotation

---

## Section 4: Central Configuration Manager

### 4.1 Storage
- [ ] Use `androidx.datastore.preferences.core.Preferences` with single key `"app_config"`
- [ ] Dependencies: `datastore-preferences:1.0.0`, `kotlinx-serialization-json:1.6.0`

### 4.2 ConfigManager Implementation
- [ ] Singleton pattern with Context
- [ ] `configFlow: Flow<AppConfig>` that emits on changes
- [ ] `suspend fun updateGeneral(general: GeneralSettings)`
- [ ] `suspend fun updateAdmin(admin: AdminConfig)` with museum slug validation
- [ ] `suspend fun addScheduledRun(run: ScheduledRun)`
- [ ] `suspend fun removeScheduledRun(runId: String)`
- [ ] Extension functions `toAppConfig()` and `withConfig()`

### 4.3 JSON Builder for Go Agent
- [ ] `buildAgentConfig(run: ScheduledRun, config: AppConfig): String`
- [ ] Exact field names matching Go struct (case-sensitive)
- [ ] Includes credential resolution logic
- [ ] Uses `preferredslug` (slug, not name)

---

## Section 5: UI Screens (Jetpack Compose)

### Navigation Structure
- [ ] BottomNavigation with four items: Dashboard, Config, Schedule, Logs
- [ ] Using `androidx.compose.material3` stable components

### 5.1 DashboardScreen
- [ ] State: `config by configManager.configFlow.collectAsState()`
- [ ] State: `isRunning` from BookingForegroundService via StateFlow
- [ ] Status Card showing "Running" or "Idle"
- [ ] Next Run Countdown from sorted `scheduledRuns`
- [ ] Start Now Button (creates run with +30s delay)
- [ ] Stop Button (calls BookingForegroundService.stop())
- [ ] Quick Stats: Active Site, Mode, Preferred Museum (by name)

### 5.2 ConfigScreen (PIN-protected)
- [ ] PIN Dialog with hardcoded "1234"
- [ ] TabRow with "General" and "Sites" tabs

#### 5.2.1 General Tab
- [ ] Mode: SegmentedButton ("Alert", "Booking")
- [ ] Strike Time: OutlinedTextField with TimePicker
- [ ] Preferred Days: FlowRow of FilterChip
- [ ] ntfy Topic: OutlinedTextField
- [ ] Preferred Museum Dropdown using ExposedDropdownMenuBox
  - Displays museum names
  - Stores slug in `preferredMuseumSlug`
- [ ] Performance Tuning expanded section
- [ ] Save Button calls `configManager.updateGeneral()`

#### 5.2.2 Sites Tab
- [ ] Active Site Dropdown from `config.admin.sites.keys`
- [ ] Site-specific fields: Base URL, Availability Endpoint, Digital, Physical, Location
- [ ] Museums Section:
  - LazyColumn of Museum items with edit/delete
  - FloatingActionButton for add
  - Bulk import dialog (name:slug:museumId format)
- [ ] Credentials Section:
  - LazyColumn of CredentialSet cards
  - Edit, Delete, Star (default) buttons
  - FloatingActionButton for add credential
- [ ] Save Button calls `configManager.updateAdmin()`
- [ ] Validation: defaultCredentialId must exist; clear if deleted

### 5.3 ScheduleScreen
- [ ] State: `config by configManager.configFlow.collectAsState()`
- [ ] Site Dropdown from `config.admin.sites.keys`
- [ ] Museum Dropdown from selected site's museums (displays names, stores slugs)
- [ ] Credential Dropdown with "Use default" option
- [ ] Mode Dropdown ("Alert" / "Booking")
- [ ] Date/Time Picker for future timestamp
- [ ] Schedule Button creates ScheduledRun
- [ ] Scheduled Runs List sorted by dropTimeMillis with delete

### 5.4 LogsScreen
- [ ] Live Logs observing `LogManager.logFlow`
- [ ] Auto-scroll Toggle
- [ ] Export Button with share sheet
- [ ] Clear Button

---

## Section 6: Scheduling & Background Execution

### 6.1 AlarmScheduler
- [ ] `scheduleRun(run: ScheduledRun)` using `setExactAndAllowWhileIdle`
- [ ] `cancelRun(runId: String)`
- [ ] PendingIntent with immutable flag

### 6.2 AlarmReceiver
- [ ] BroadcastReceiver that extracts run data from intent
- [ ] Calls `BookingForegroundService.start(context, run)`

### 6.3 BootReceiver
- [ ] BroadcastReceiver for `ACTION_BOOT_COMPLETED`
- [ ] Re-schedules all runs from config

### 6.4 BookingForegroundService
- [ ] Extends `LifecycleService`
- [ ] Holds reference to `MobileAgent` from AAR
- [ ] `StateFlow<Boolean>` for `isRunning`
- [ ] `onStartCommand`: loads config, builds JSON, sets callbacks, starts agent
- [ ] Notification with channel "booking_service"
- [ ] Stop action in notification

---

## Section 7: LogManager

- [ ] `MutableSharedFlow<LogEntry>` with buffer capacity 100
- [ ] In-memory buffer with MAX_BUFFER_SIZE = 500
- [ ] File logging to `logs.txt`
- [ ] `addLog(level, message)` method
- [ ] `exportLogs(context): Uri` using FileProvider
- [ ] `clearInMemory()` method
- [ ] LogEntry data class with timestamp, level, message

---

## Section 8: Go Agent Integration

- [ ] AAR location: `app/libs/booking.aar`
- [ ] Build script: `scripts/build-go.sh`
- [ ] Dependency in build.gradle.kts
- [ ] MobileAgent methods: start, stop, isRunning, setLogCallback, setStatusCallback
- [ ] Log callback parses JSON and calls LogManager.addLog

---

## Section 9: Error Handling & Validation

- [ ] Config validation before saving admin (defaultCredentialId exists)
- [ ] Schedule validation (site/museum/credential exist)
- [ ] Go agent errors logged
- [ ] Alarm scheduling validates dropTimeMillis is in future

---

## Section 10: Security

- [ ] PIN hardcoded "1234" in ConfigScreen dialog
- [ ] Note for production: use EncryptedSharedPreferences

---

## Section 11: Build Configuration

### 11.1 Project-level build.gradle.kts
- [ ] Plugins: com.android.application 8.2.0, kotlin.android 1.9.20, kotlin.serialization 1.9.20

### 11.2 App-level build.gradle.kts
- [ ] namespace = "com.booking.bot"
- [ ] compileSdk = 34, minSdk = 26, targetSdk = 34
- [ ] ABI filters: armeabi-v7a, arm64-v8a, x86, x86_64
- [ ] ABI splits configured
- [ ] ProGuard enabled for release
- [ ] Java 17 compatibility
- [ ] Compose features enabled
- [ ] All dependencies as specified

### 11.3 ProGuard Rules
- [ ] Keep go.** and mobile.** classes
- [ ] Keep serialization annotations

---

## Section 12: Build Script for Go AAR

- [ ] scripts/build-go.sh exists and is executable
- [ ] Uses gomobile bind with androidapi 21

---

## Section 13: Testing & Acceptance Criteria

- [ ] App builds without errors on all ABIs
- [ ] Runs on Android 8.0 (API 26) and Android 14 (API 34)
- [ ] PIN 1234 grants access
- [ ] General settings save and persist
- [ ] Museum CRUD operations work
- [ ] Bulk import works
- [ ] Credential CRUD with default marking works
- [ ] Schedule screen shows correct data
- [ ] Start Now creates immediate run
- [ ] Scheduled runs trigger at exact time
- [ ] Logs displayed live and export works
- [ ] Boot receiver restores alarms
- [ ] No experimental API warnings
- [ ] Release APK builds

---

## Section 14: Museum Name Display & Mapping Logic

- [ ] Museums stored as slug -> Museum map
- [ ] UI displays museum.name
- [ ] Selection stores slug
- [ ] GeneralSettings.preferredMuseumSlug stores slug
- [ ] ScheduledRun.museumSlug stores slug
- [ ] JSON to Go agent uses slug for preferredslug

---

## Current State Assessment

**Package Structure Issue:** Current app uses `com.apptcheck.agent` but spec requires `com.booking.bot`

**Data Model Issues:**
- Missing `CredentialSet` - currently using single loginUsername/loginPassword/loginEmail per site
- Missing `defaultCredentialId` field in SiteConfig
- Using `UserConfig` instead of `GeneralSettings`
- Missing `@Serializable` annotations
- Missing `credentialId` in ScheduledRun

**ConfigManager Issues:**
- Not using kotlinx.serialization for proper JSON
- Missing validation logic for museum slugs
- buildAgentConfig doesn't handle multiple credentials

**UI Issues:**
- Package name mismatch
- Missing proper reactive architecture with collectAsState
- AdminConfigScreen missing Credentials section with multiple credential sets
- UserConfigScreen needs to be renamed/refactored to General Tab
- Dashboard needs proper countdown and reactive updates

**Scheduling Issues:**
- Need to verify AlarmScheduler implementation matches spec exactly
- Need to verify BootReceiver implementation

**Service Issues:**
- Need to verify BookingForegroundService has StateFlow for isRunning
- Need to verify proper callback handling

---

## Implementation Plan

1. **Phase 1: Data Models** - Rewrite all data models in `com.booking.bot.data` package
2. **Phase 2: ConfigManager** - Implement with kotlinx.serialization
3. **Phase 3: LogManager** - Implement complete logging system
4. **Phase 4: Scheduling** - Implement AlarmScheduler, AlarmReceiver, BootReceiver
5. **Phase 5: Service** - Implement BookingForegroundService with Go integration
6. **Phase 6: UI - Dashboard** - Rewrite DashboardScreen
7. **Phase 7: UI - Config** - Rewrite ConfigScreen with tabs
8. **Phase 8: UI - Schedule** - Rewrite ScheduleScreen
9. **Phase 9: UI - Logs** - Rewrite LogsScreen
10. **Phase 10: Navigation** - Implement BottomNavigation
11. **Phase 11: Build Config** - Update build.gradle files
12. **Phase 12: Testing** - Verify all acceptance criteria
