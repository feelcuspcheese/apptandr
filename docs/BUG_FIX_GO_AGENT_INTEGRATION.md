# Bug Fix: Go Agent Not Actually Triggered - Shows [SIMULATED] Logs

## Issue Description

When clicking "Start Now" on the Dashboard screen, the UI shows "Starting agent in 30 seconds..." but the Logs screen displays `[SIMULATED] start` and `[SIMULATED] stop` messages instead of actual Go agent logs. The Go booking agent was not being triggered with the real configuration from DataStore.

### Expected Behavior (per TECHNICAL_SPEC.md)
1. Dashboard "Start Now" button should trigger the Go agent with actual configuration
2. Go agent logs should appear in the Logs screen with real execution details
3. Configuration should be loaded from DataStore via ConfigManager.buildAgentConfig()
4. Logs should follow the format from the Go agent's logrus logger

### Actual Behavior
- Service was calling `simulateGoAgentRun()` instead of the real Go agent
- Logs showed `[SIMULATED]` prefix indicating fake execution
- No actual booking checks were performed

## Root Cause Analysis

The `BookingForegroundService.kt` had placeholder code that simulated agent execution instead of integrating with the Go AAR (booking.aar):

```kotlin
// OLD CODE - simulateGoAgentRun() was called instead of real agent
private fun simulateGoAgentRun() {
    updateNotification("Running booking agent...")
    LogManager.addLog("INFO", "[SIMULATED] Go agent started in alert mode")
    // ... simulated delay and completion
}
```

The Go AAR (`booking.aar`) existed in `libs/` but was never integrated into the service.

## Solution Design

Following TECHNICAL_SPEC.md sections 5 (Go Agent Integration) and 8 (Background Execution):

1. **Create GoAgentWrapper**: Kotlin wrapper for the MobileAgent AAR
2. **Integrate with BookingForegroundService**: Replace simulation with real agent calls
3. **Set up callbacks**: Forward Go logs to LogManager, track status changes
4. **Remove simulation code**: Delete the placeholder `simulateGoAgentRun()` method

### Architecture Alignment

```
┌─────────────────────────────────────┐
│   BookingForegroundService          │
│                                     │
│  ┌───────────────────────────────┐  │
│  │  GoAgentWrapper               │  │
│  │  (Kotlin wrapper)             │  │
│  │                               │  │
│  │  - initialize()               │  │
│  │  - setLogCallback()           │  │
│  │  - setStatusCallback()        │  │
│  │  - start(configJson)          │  │
│  │  - stop()                     │  │
│  │  - isRunning()                │  │
│  └───────────────┬───────────────┘  │
│                  │                   │
│                  ▼                   │
│         ┌─────────────────┐         │
│         │ MobileAgent     │         │
│         │ (Go AAR)        │         │
│         └─────────────────┘         │
└─────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────┐
│   ConfigManager.buildAgentConfig()  │
│   (Builds JSON from DataStore)      │
└─────────────────────────────────────┘
```

## Implementation Details

### 1. Created GoAgentWrapper.kt

**File**: `android-app/app/src/main/java/com/apptcheck/agent/gomobile/GoAgentWrapper.kt`

```kotlin
package com.apptcheck.agent.gomobile

import mobile.MobileAgent

object GoAgentWrapper {
    private var mobileAgent: MobileAgent? = null
    private var isInitialized = false
    
    fun initialize() { /* ... */ }
    fun setLogCallback(callback: (String) -> Unit) { /* ... */ }
    fun setStatusCallback(callback: (String) -> Unit) { /* ... */ }
    fun start(configJson: String): Boolean { /* ... */ }
    fun stop() { /* ... */ }
    fun isRunning(): Boolean { /* ... */ }
}
```

### 2. Updated BookingForegroundService.kt

**Changes**:
- Added import for `GoAgentWrapper`
- Replaced `launchGoAgent()` to use real Go agent
- Updated `stopAgent()` to call `GoAgentWrapper.stop()`
- Removed `simulateGoAgentRun()` method

**Key Integration Code**:

```kotlin
private fun launchGoAgent() {
    val run = currentRun ?: return
    
    try {
        val configManager = ConfigManager(this)
        val configJson = configManager.buildAgentConfig(run)
        
        LogManager.addLog("INFO", "Built agent config for site ${run.siteKey}, museum ${run.museumSlug}")
        
        // Initialize GoAgentWrapper and set up callbacks
        GoAgentWrapper.initialize()
        
        // Set up log callback to forward Go logs to LogManager
        GoAgentWrapper.setLogCallback { logMessage ->
            LogManager.addLog("GO_AGENT", logMessage)
            updateNotification(logMessage)
        }
        
        // Set up status callback to track running state
        GoAgentWrapper.setStatusCallback { status ->
            LogManager.addLog("INFO", "Go agent status: $status")
            if (status == "running") {
                isRunning = true
                updateNotification("Running booking agent...")
            } else if (status == "stopped" || status == "finished") {
                updateNotification("Completed")
                stopAgent()
            }
        }
        
        // Start the Go agent with the configuration
        val started = GoAgentWrapper.start(configJson)
        
        if (started) {
            LogManager.addLog("INFO", "[REAL] Go agent started in ${run.mode} mode")
            updateNotification("Running booking agent...")
        } else {
            LogManager.addLog("ERROR", "[REAL] Failed to start Go agent")
            updateNotification("Error: Failed to start agent")
            stopAgent()
        }
        
    } catch (e: Exception) {
        LogManager.addLog("ERROR", "Failed to build/start agent config: ${e.message}")
        updateNotification("Error: ${e.message}")
        stopAgent()
    }
}
```

### 3. Log Flow

```
Go Agent (mobile.go)
    ↓ (via LogCallbackHook)
GoAgentWrapper.setLogCallback()
    ↓
LogManager.addLog("GO_AGENT", message)
    ↓
LogEntry emitted to _logFlow
    ↓
LogsScreen observes and displays
```

## Files Modified

1. **New File**: `android-app/app/src/main/java/com/apptcheck/agent/gomobile/GoAgentWrapper.kt`
   - Created wrapper for MobileAgent AAR
   - Implements all methods from TECHNICAL_SPEC.md section 5

2. **Modified**: `android-app/app/src/main/java/com/apptcheck/agent/service/BookingForegroundService.kt`
   - Added GoAgentWrapper integration
   - Removed simulation code
   - Set up proper callbacks for logs and status

## Verification Steps

After building the Go AAR properly with gomobile:

1. **Configure Admin Settings**:
   - Go to Admin Config screen
   - Set login credentials for SPL or KCLS
   - Configure museums

2. **Start Agent from Dashboard**:
   - Click "Start Now" button
   - Should see "Starting agent in 30 seconds..."
   - After 30 seconds, service starts

3. **Check Logs Screen**:
   - Should see `[REAL] Go agent started in <mode> mode`
   - Should see actual Go agent logs with levels: `[INFO]`, `[ERROR]`, etc.
   - Should NOT see `[SIMULATED]` prefix anymore
   - Logs should show actual booking check progress

4. **Expected Log Output**:
   ```
   [INFO] Built agent config for site spl, museum seattle-art-museum
   [INFO] [REAL] Go agent started in alert mode
   [GO_AGENT] [INFO] Starting availability check...
   [GO_AGENT] [INFO] Checking museum: Seattle Art Museum
   [GO_AGENT] [INFO] Found available slot!
   [GO_AGENT] [INFO] Sending notification to ntfy topic...
   [INFO] Go agent status: finished
   ```

## Centralization Compliance

✅ **Single Config Source**: Uses `ConfigManager.buildAgentConfig()` to load from DataStore  
✅ **Single Log Manager**: All Go logs forwarded to `LogManager.addLog()`  
✅ **Per-Site Isolation**: Config includes correct site/museum from selected run  
✅ **Reactive UI**: Status updates via callbacks flow to UI through LogManager  

## Dependencies

- **Go AAR Required**: The `booking.aar` file must be built from Go source using gomobile
- **Package Structure**: Go package must be `mobile` to match import in GoAgentWrapper
- **API Compatibility**: MobileAgent class must expose:
  - `setLogCallback(Function1<String, Unit>)`
  - `setStatusCallback(Function1<String, Unit>)`
  - `start(String): Boolean`
  - `stop()`
  - `isRunning(): Boolean`

## Test Plan Updates

See TEST_PLAN.md for updated test cases:
- TC-07: Dashboard Start/Stop (updated to verify real agent execution)
- TC-12: Log Display (updated to verify Go agent logs appear)
- New TC-21: Go Agent Integration Verification

## Notes

- If `booking.aar` is empty/not built yet, the app will throw ClassNotFoundException
- Build command for Go AAR: `gomobile bind -target android -androidapi 23 -o android-app/app/libs/booking.aar ./mobile`
- The wrapper uses object singleton pattern for simplicity (no DI framework per TECHNICAL_SPEC.md)
