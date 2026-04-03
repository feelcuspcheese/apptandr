# Build Fixes Log

This document tracks all build fixes applied to the project for future validation and reference.

## Fix 1: Android Resource Linking Error - Missing Theme.BookingBot

**Date:** Applied during CI build fix

**Issue:**
```
ERROR: /home/runner/work/apptandr/apptandr/android-app/app/src/main/AndroidManifest.xml:14:5-74:19: AAPT: error: resource style/Theme.BookingBot (aka com.booking.bot:style/Theme.BookingBot) not found.
ERROR: /home/runner/work/apptandr/apptandr/android-app/app/src/main/AndroidManifest.xml:25:9-33:20: AAPT: error: resource style/Theme.BookingBot (aka com.booking.bot:style/Theme.BookingBot) not found.
```

**Root Cause:**
The `AndroidManifest.xml` referenced a theme `@style/Theme.BookingBot` that was not defined in any of the resource files. The `themes.xml` file only contained `Theme.AppointmentAgent`.

**Solution:**
1. Added the missing `Theme.BookingBot` style definition to `/workspace/android-app/app/src/main/res/values/themes.xml`:
   ```xml
   <style name="Theme.BookingBot" parent="android:Theme.Material.Light.NoActionBar">
       <item name="android:statusBarColor">@color/purple_700</item>
   </style>
   ```

2. Removed the deprecated `android:extractNativeLibs="true"` attribute from the `<application>` tag in `AndroidManifest.xml` as per AGP warnings. This attribute should not be specified in the AndroidManifest.xml file.

**Files Modified:**
- `android-app/app/src/main/res/values/themes.xml` - Added Theme.BookingBot style
- `android-app/app/src/main/AndroidManifest.xml` - Removed extractNativeLibs attribute

**Verification:**
The build should now proceed past the `processReleaseResources` task without the AAPT resource linking errors.

---

## Bug Fixes - Go Agent & Android App Integration

**Date:** Applied during bug fix session

### Bug 1a + 5 (Combined): Go Agent Silently Hangs, No Logs Ever Appear

**Files Modified:**
- `mobile/mobile.go`

**Root Causes (Three Interlocking Issues):**

1. **Bug 1a — Deadlock in Start():**
   - `Start()` held `m.mu.Lock()` via `defer m.mu.Unlock()`. When `json.Unmarshal` failed, it called `m.fireLog("ERROR", ...)`, which tried to acquire `m.mu.RLock()`. Go's `sync.RWMutex` is not reentrant — the same goroutine deadlocked itself. The JNI call never returned. Android logged "Attempting to start Go agent" then went silent permanently.

2. **Bug 1b — Race Condition: IsRunning() Returns False Immediately After Start():**
   - `mobile.Start()` launched a goroutine and returned `true`. Kotlin's polling loop called `IsRunning()` before the goroutine was scheduled. `IsRunning()` delegated to `agentInst.IsRunning()` → `agent.a.running`, which is set inside `agent.Start()` in the goroutine — not before launching it. Result: the polling loop exited instantly, `cleanupAndStop()` fired, removing the run from the schedule even though the Go agent was actually running.

3. **Bug 5 — Duration Fields Fail JSON Unmarshal:**
   - `config.AppConfig` duration fields (`CheckWindow`, `CheckInterval`, etc.) are `time.Duration` (int64 nanoseconds). Kotlin sends them as human-readable strings like "60s", "0.81s". Go's `encoding/json` rejects a JSON string where it expects an int64 — returning an unmarshal error that triggered the Bug 1a deadlock, making the failure completely invisible.

**Solutions:**

1. **Fix for Bug 1a (Deadlock):**
   - Replaced `defer m.mu.Unlock()` with explicit `m.mu.Unlock()` calls, always releasing the lock before calling `fireLog`/`fireStatus`.
   - In `Start()`, the lock is now released immediately after checking if agent is running and before calling `fireLog` on error.
   - Similarly, on JSON unmarshal error and dropTime parse error, the lock is released before calling `fireLog`.

2. **Fix for Bug 1b (Race Condition):**
   - Added `mobileRunning bool` field to `MobileAgent` struct.
   - Set `mobileRunning = true` while holding the write lock, BEFORE launching the goroutine.
   - The goroutine clears `mobileRunning = false` when finished (also under lock).
   - `IsRunning()` now reads `mobileRunning` — guaranteed true from the moment `Start()` returns.

3. **Fix for Bug 5 (Duration Unmarshal):**
   - Created new `intermediateConfig` struct with plain string duration fields (`CheckWindow`, `CheckInterval`, `RequestJitter`, `PreWarmOffset`, `RestCycleDuration`).
   - After JSON unmarshal, each string is converted via `time.ParseDuration()` before building `config.AppConfig`.
   - Default values are provided for empty or invalid duration strings.

**Code Changes in mobile/mobile.go:**
- Added `mobileRunning bool` field to `MobileAgent` struct
- Added `intermediateConfig` struct with string duration fields
- Changed `Start()` to use explicit unlock calls instead of defer
- Added `parseDuration` helper function with defaults
- Modified `IsRunning()` to return `mobileRunning` instead of delegating to agent

---

### Bug 2: Dashboard Shows "Running" Forever

**Files Modified:**
- `android-app/app/src/main/java/com/booking/bot/service/BookingForegroundService.kt`

**Root Cause:**
Consequence of Bugs 1a + 1b in mobile.go. Since `cleanupAndStop()` never ran (deadlock case), `_isRunning.value` stayed true. Additionally, `BookingForegroundService.kt` was ignoring `mobileAgent?.start()`'s return value. If `Start()` returned false, the service still logged "started successfully" and entered the polling loop.

**Solution:**
- Modified the service to check the Boolean return value of `mobileAgent?.start(agentConfigJson)`.
- If `Start()` returns false, the service now logs an error and immediately calls `cleanupAndStop(run.id)` to properly reset the UI state.

**Code Changes:**
```kotlin
val startedSuccessfully = mobileAgent?.start(agentConfigJson)
if (startedSuccessfully != true) {
    LogManager.addLog("ERROR", "Go agent failed to start for run ${run.id}")
    cleanupAndStop(run.id)
    return@launch
}
```

---

### Bug 3: Log Screen Spammed with "Configuration Loaded"

**Files Modified:**
- `android-app/app/src/main/java/com/booking/bot/data/ConfigManager.kt`
- `android-app/app/src/main/java/com/booking/bot/ui/screens/LogsScreen.kt`

**Root Cause in ConfigManager.kt:**
The "Configuration loaded" log was emitted inside `configFlow`'s `.map {}` operator. DataStore emits to every new subscriber and on every write. With 4 screens each subscribing when navigated to, plus every config save triggering re-emission to all collectors, this produced 5–20 log lines per second of app usage.

**Root Cause in LogsScreen.kt:**
Loading the buffer snapshot and then subscribing to the flow could add duplicate entries if a log fired between the two steps.

**Solutions:**

1. **ConfigManager.kt Fix:**
   - Added `private val configLoadedOnce = AtomicBoolean(false)` at module level.
   - The log fires only the first time the flow emits, using `compareAndSet(false, true)`.

2. **LogsScreen.kt Fix:**
   - Added `var lastBufferedTs by remember { mutableStateOf(0L) }` watermark.
   - When loading buffered logs, capture the maximum timestamp from the buffer.
   - When collecting from `logFlow`, skip entries with timestamps ≤ `lastBufferedTs`.
   - Update `lastBufferedTs` when adding new entries.

**Code Changes in ConfigManager.kt:**
- Added import: `import java.util.concurrent.atomic.AtomicBoolean`
- Added module-level `configLoadedOnce` AtomicBoolean
- Wrapped the "Configuration loaded" log in `if (configLoadedOnce.compareAndSet(false, true)) { ... }`

**Code Changes in LogsScreen.kt:**
- Added `lastBufferedTs` state variable
- Modified buffer loading to capture max timestamp
- Added timestamp comparison in logFlow collection to skip duplicates

---

### Bug 4: "Next Run" Countdown Only Updates on Screen Switch

**Files Modified:**
- `android-app/app/src/main/java/com/booking/bot/ui/screens/DashboardScreen.kt`

**Root Cause:**
`timeUntil` was computed as `nextRun.dropTimeMillis - System.currentTimeMillis()` inside a `config?.let {}` block. This only recomposes when the config StateFlow emits a new value (i.e., on DataStore writes), not every second.

Additionally, `var countdownJob: Job? = remember { null }` was a plain local var (not `mutableStateOf`), so job references were lost on recomposition.

**Solutions:**

1. **Added currentTime State:**
   - Added `var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }`
   - Added `LaunchedEffect(Unit)` that ticks every second: `while (true) { delay(1000); currentTime = System.currentTimeMillis() }`
   - The countdown card now uses `currentTime` instead of direct `System.currentTimeMillis()`, so Compose recomposes every second automatically.

2. **Fixed Job Reference Loss:**
   - Changed `var countdownJob: Job? = remember { null }` to `val countdownJobState = remember { mutableStateOf<Job?>(null) }`
   - Updated all references to use `countdownJobState.value` instead of `countdownJob`

**Code Changes:**
- Added `currentTime` state and ticking LaunchedEffect
- Changed `countdownJob` to `countdownJobState` with mutableStateOf
- Updated filter and timeUntil calculation to use `currentTime`

---

## Summary of All Bug Fixes

| Bug # | Description | Files Modified | Status |
|-------|-------------|----------------|--------|
| 1a | Deadlock in Start() due to RWMutex reentrancy | mobile/mobile.go | Fixed |
| 1b | Race condition: IsRunning() returns false immediately | mobile/mobile.go | Fixed |
| 2 | Dashboard shows "Running" forever | BookingForegroundService.kt | Fixed |
| 3 | Log screen spammed with "Configuration loaded" | ConfigManager.kt, LogsScreen.kt | Fixed |
| 4 | "Next Run" countdown only updates on screen switch | DashboardScreen.kt | Fixed |
| 5 | Duration fields fail JSON unmarshal | mobile/mobile.go | Fixed |

---

## Notes for Future Reference

- When adding new themes to the AndroidManifest.xml, ensure they are properly defined in the `res/values/themes.xml` file.
- The `android:extractNativeLibs` attribute should be configured in the build.gradle file, not in the AndroidManifest.xml, as per modern AGP guidelines.
- Go's `sync.RWMutex` is NOT reentrant — never call methods that acquire locks while already holding a lock in the same goroutine.
- When launching goroutines that set internal state, ensure the state is visible to callers before the goroutine is scheduled.
- For JSON unmarshaling of duration fields from Kotlin/Android, use string representations and parse with `time.ParseDuration()`.
- Use `AtomicBoolean` for one-time actions in reactive flows that may emit multiple times.
- Use `mutableStateOf` for Compose state that needs to survive recomposition (like Job references).
- For live-updating countdowns in Compose, use a ticking state variable rather than direct `System.currentTimeMillis()` calls.

