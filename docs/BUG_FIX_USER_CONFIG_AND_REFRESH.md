# Bug Fix: User Config Museum Dropdown and Schedule Screen Refresh

## Issue Summary

Two critical bugs were reported affecting configuration persistence and UI refresh:

1. **User Config Preferred Museum Not a Dropdown**: The "Preferred Museum Slug" field in User Config was a text input instead of a dropdown populated from admin-configured museums.

2. **Schedule Screen Not Refreshing**: After saving changes in Admin Config or User Config, the Schedule screen did not reflect the updated values (Site, Museum, Mode) when navigating back.

## Root Cause Analysis

### Bug 1: Museum Text Input Instead of Dropdown

**Location**: `UserConfigScreen.kt`

**Problem**: The Preferred Museum field was implemented as a simple `OutlinedTextField`:
```kotlin
OutlinedTextField(
    value = preferredSlug,
    onValueChange = { preferredSlug = it },
    label = { Text("Preferred Museum Slug") },
    ...
)
```

This allowed users to type any text instead of selecting from configured museums, leading to:
- Invalid museum slugs
- No validation against admin-configured museums
- Poor UX requiring manual slug entry

**Evidence**: Code review showed no integration with `ConfigManager` to load available museums.

### Bug 2: Schedule Screen Stale Data

**Location**: `ScheduleScreen.kt`, `ScheduleViewModel.kt`

**Problem**: The Schedule screen loaded config only once in `init {}` block:
```kotlin
init {
    loadConfig()
}
```

When user navigated to Admin Config, added museums, saved, then returned to Schedule:
- `loadConfig()` was never called again
- UI showed stale museum list
- Mode field showed hardcoded default "alert" instead of saved user mode
- Site list didn't reflect new sites

**Evidence**: 
1. No `LaunchedEffect` to reload config when screen becomes visible
2. `loadConfig()` didn't load `config.user.mode` into `selectedMode`

## Fixes Applied

### Fix 1: Museum Dropdown in User Config

**File**: `android-app/app/src/main/java/com/apptcheck/agent/ui/screens/UserConfigScreen.kt`

**Changes**:
1. Added `ConfigManager` to load admin config
2. Created `availableMuseums` state variable
3. Replaced `OutlinedTextField` with `ExposedDropdownMenuBox`
4. Populated dropdown from `config.admin.sites[activeSite].museums.keys`

```kotlin
// Load admin config to get museums for dropdown
val configManager = remember { ConfigManager(viewModel.androidApplication.applicationContext) }
var availableMuseums by remember { mutableStateOf<List<String>>(emptyList()) }

LaunchedEffect(Unit) {
    try {
        val config = configManager.loadConfig()
        val siteKey = config.admin.activeSite
        availableMuseums = config.admin.sites[siteKey]?.museums?.keys?.toList() ?: emptyList()
    } catch (e: Exception) {
        // Keep empty list on error
    }
}

// Dropdown implementation
ExposedDropdownMenuBox(
    expanded = museumExpanded,
    onExpandedChange = { museumExpanded = !museumExpanded }
) {
    OutlinedTextField(
        value = preferredSlug,
        onValueChange = {},
        readOnly = true,
        label = { Text("Preferred Museum") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = museumExpanded) },
        modifier = Modifier.fillMaxWidth(),
        enabled = availableMuseums.isNotEmpty()
    )
    ExposedDropdownMenu(...)
}
```

**Benefits**:
- Users can only select valid, configured museums
- No manual slug typing required
- Automatically updates when admin adds/removes museums
- Shows error message if no museums configured

### Fix 2: Schedule Screen Auto-Refresh

**File**: `android-app/app/src/main/java/com/apptcheck/agent/ui/screens/ScheduleScreen.kt`

**Changes**:
Added `LaunchedEffect` to reload config when screen becomes visible:

```kotlin
// Reload config when screen becomes visible (e.g., after returning from Admin Config)
LaunchedEffect(Unit) {
    viewModel.loadConfig()
}
```

**File**: `android-app/app/src/main/java/com/apptcheck/agent/viewmodel/ScheduleViewModel.kt`

**Changes**:
1. Enhanced `loadConfig()` to load `config.user.mode`
2. Updated `UiState` copy to include `selectedMode`

```kotlin
fun loadConfig() {
    viewModelScope.launch {
        try {
            val config = configManager.loadConfig()
            // ... existing code ...
            
            // Update mode from user config - this ensures mode reflects saved config
            val savedMode = config.user.mode
            
            _uiState.value = _uiState.value.copy(
                selectedSite = validCurrentSite,
                selectedMode = savedMode,  // Load saved mode
                availableSites = availableSites,
                availableMuseums = museums,
                scheduledRuns = scheduledRuns
            )
        } catch (e: Exception) {
            // On error, keep defaults
        }
    }
}
```

**Benefits**:
- Schedule screen always shows latest saved configuration
- Mode field reflects actual user preference, not hardcoded default
- Museum list updates immediately after admin adds new museums
- No app restart required to see changes

## Files Modified

| File | Changes |
|------|---------|
| `UserConfigScreen.kt` | Added museum dropdown with ConfigManager integration |
| `ScheduleScreen.kt` | Added LaunchedEffect to reload config on visibility |
| `ScheduleViewModel.kt` | Enhanced loadConfig() to load user mode |
| `DashboardScreen.kt` | Minor comment improvement for clarity |
| `TEST_PLAN.md` | Added AC-32 through AC-34, TC-32 through TC-34 |

## Verification Steps

### TC-32: User Config Museum Dropdown
1. Configure museums in Admin Config (e.g., "seattle-art-museum", "zoo")
2. Navigate to User Config
3. Click "Preferred Museum" field
4. Verify dropdown shows configured museums
5. Select a museum and save
6. Navigate away and back - verify selection persists

### TC-33: Schedule Screen Refresh
1. Open Schedule screen, note current values
2. Go to Admin Config, add new museum to SPL
3. Go to User Config, change mode to "booking"
4. Save both configs
5. Return to Schedule screen
6. Verify:
   - Museum dropdown includes new museum
   - Mode shows "booking"
   - All fields reflect latest saves

### TC-34: Mode Persistence
1. Set user mode to "booking" in User Config
2. Close and reopen app
3. Open Schedule screen
4. Verify Mode dropdown shows "booking" (not "alert")

## Alignment with Central Configuration Manager

All fixes maintain strict adherence to TECHNICAL_SPEC.md section 3:

✅ **Single Source of Truth**: All screens load from `ConfigManager.loadConfig()` → DataStore
✅ **Per-Site Isolation**: Museums loaded per `config.admin.sites[siteKey].museums`
✅ **Reactive Updates**: StateFlow + LaunchedEffect pattern ensures UI stays in sync
✅ **Schema Compliance**: JSON structure matches `default_config.yaml`:
   - `user.preferredSlug` ↔ User's preferred museum
   - `admin.sites[].museums` ↔ Available museums per site
   - `user.mode` ↔ Run mode (alert/booking)

## Impact Assessment

- **No Breaking Changes**: Existing functionality preserved
- **Backward Compatible**: Works with existing saved configs
- **Performance**: Minimal impact (single config load on screen visibility)
- **Centralization**: Maintained - all data flows through ConfigManager

## Related Acceptance Criteria

- AC-32: User Config Museum Dropdown
- AC-33: Schedule Screen Refresh After Admin Config Changes
- AC-34: Mode Persistence in Schedule Screen

## Conclusion

Both bugs have been resolved by:
1. Implementing proper dropdown UI component with ConfigManager integration
2. Adding lifecycle-aware config reloading in Schedule screen
3. Ensuring all fields load from saved configuration, not hardcoded defaults

The fixes improve UX by preventing invalid inputs and ensuring real-time reflection of configuration changes across all screens.
