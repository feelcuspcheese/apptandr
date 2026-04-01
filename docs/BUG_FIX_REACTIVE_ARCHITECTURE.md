# Bug Fixes: Flow-based Reactive Architecture and Centralized ViewModel

## Overview
This document describes fixes for multiple bugs related to reactive data flow, site/museum display, and centralized configuration management across screens.

## Bugs Fixed

### Bug 1: DataStore Writes Weren't Notifying Other Screens

**Problem:**
- Changes made in one screen (e.g., Admin Config) were not reflected in other screens (e.g., User Config, Schedule)
- Each screen loaded config independently without reactive updates
- Users had to restart the app to see changes propagate

**Root Cause:**
ViewModels were using `MutableStateFlow` but only emitting values on initial load or explicit save. There was no continuous Flow collection from DataStore that would automatically emit updates when any screen modified the config.

**Solution:**
Implement a Flow-based reactive architecture where:
1. `ConfigManager` exposes `configFlow: Flow<AppConfig>` that emits whenever DataStore changes
2. ViewModels collect this Flow continuously, not just on init
3. All screens observe the same underlying data source reactively

**Files Modified:**
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/data/ConfigManager.kt`
  - Added `val configFlow: Flow<AppConfig>` that maps DataStore changes
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/viewmodel/AdminConfigViewModel.kt`
  - Changed to collect `configFlow` in viewModelScope
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/viewmodel/UserConfigViewModel.kt`
  - Changed to collect `configFlow` in viewModelScope
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/viewmodel/ScheduleViewModel.kt`
  - Changed to collect `configFlow` in viewModelScope

### Bug 2: Site Toggle Didn't Filter Museums Reactively

**Problem:**
- In Admin Config screen, the site dropdown was hardcoded to `listOf("spl", "kcls")`
- Changing site didn't dynamically update the displayed museums
- Museums shown were from the initially loaded config, not the selected site

**Root Cause:**
`AdminConfigScreen.kt` line 167 had hardcoded `val availableSites = listOf("spl", "kcls")` instead of loading sites dynamically from the admin config.

**Solution:**
1. Load available sites from `adminConfig.sites.keys.toList()` 
2. When site selection changes, update all site-specific fields reactively
3. Museums list updates automatically when `activeSite` changes

**Files Modified:**
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/AdminConfigScreen.kt`
  - Line 167: Changed from hardcoded list to `adminConfig.sites.keys.toList()`

### Bug 3: Slugs Displayed Instead of Friendly Names

**Problem:**
- Museum dropdown in User Config screen showed slugs (e.g., "seattle-art-museum") instead of friendly names (e.g., "Seattle Art Museum")
- Same issue in Schedule screen museum dropdown

**Root Cause:**
The dropdown items were using the museum slug/key directly as the display text instead of looking up the museum's `.name` field.

**Solution:**
1. Create a map of slug → museum name from admin config
2. Display museum names in dropdown while storing slug as value
3. Use `Museum.name` field for display as specified in TECHNICAL_SPEC.md

**Files Modified:**
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/UserConfigScreen.kt`
  - Added museum name mapping logic
  - Changed dropdown to display names instead of slugs
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/ScheduleScreen.kt`
  - Added museum name mapping logic
  - Changed dropdown to display names instead of slugs

### Bug 4: Admin Save Didn't Trigger Refresh

**Problem:**
- After saving changes in Admin Config, other screens didn't reflect the updates
- User had to navigate away and back multiple times to see changes

**Root Cause:**
ViewModels were not sharing state. Each ViewModel loaded config once on init and didn't listen for subsequent changes.

**Solution:**
All ViewModels now collect the same `configFlow` from ConfigManager. When any ViewModel saves config to DataStore, the Flow emits and all collecting ViewModels receive the update automatically.

**Files Modified:**
- All ViewModel files now use `configFlow.collect {}` pattern
- Saves go through ConfigManager which updates DataStore, triggering Flow emission

### Bug 5: Each Screen Had Independent ViewModel

**Problem:**
- Each screen created its own ViewModel instance via `viewModel()`
- No state sharing between screens
- Violates single source of truth principle

**Root Cause:**
Android's `viewModel()` function creates a ViewModel scoped to the navigation graph destination by default. Without explicit Activity-scoping, each screen gets independent instances.

**Solution:**
While we keep separate ViewModels for separation of concerns, they all share the same underlying data source:
1. All ViewModels use the same `ConfigManager` instance pattern
2. All collect from the same `configFlow` Flow
3. DataStore serves as the single source of truth
4. For true shared state, ViewModels could be Activity-scoped, but Flow-based approach achieves same result with better lifecycle management

**Design Decision:**
We maintain separate ViewModels but ensure they're reactive to the same data source. This follows MVVM best practices while achieving the desired state sharing.

### Bug 6: Dropdown Showed Nothing After Import

**Problem:**
- After importing museums in Admin Config, the museum dropdown in User Config/Schedule screens would show empty
- Timing issue: museum loading happened before import was persisted

**Root Cause:**
`LaunchedEffect(Unit)` in UserConfigScreen loaded museums once on composition. If admin config changed after this, the museum list wasn't updated.

**Solution:**
1. Museum loading now observes `configFlow` continuously
2. When admin config changes (museums imported), the Flow emits
3. UI automatically updates with new museums

**Files Modified:**
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/UserConfigScreen.kt`
  - Changed from one-time load to Flow collection
- `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/ScheduleScreen.kt`
  - Changed from one-time load to Flow collection

## Implementation Details

### Flow-based Reactive Architecture

```kotlin
// ConfigManager.kt
val configFlow: Flow<AppConfig> = context.dataStore.data.map { preferences ->
    val json = preferences[CONFIG_KEY]
    if (json != null) parseAppConfig(json) else AppConfig()
}

// ViewModel pattern
init {
    viewModelScope.launch {
        configManager.configFlow.collect { config ->
            _uiState.value = config.toUiState()
        }
    }
}
```

### Museum Name Display

```kotlin
// Get museum names for dropdown
val museumNames = remember(adminConfig) {
    adminConfig.sites[activeSite]?.museums?.values?.map { it.name } ?: emptyList()
}

// Map name back to slug for storage
val museumSlugMap = remember(adminConfig) {
    adminConfig.sites[activeSite]?.museums?.values?.associateBy({ it.name }, { it.slug }) ?: emptyMap()
}

// In dropdown
DropdownMenuItem(
    text = { Text(museumName) },  // Display name
    onClick = {
        preferredSlug = museumSlugMap[museumName] ?: ""  // Store slug
    }
)
```

## Testing

### Manual Test Scenarios

1. **Cross-Screen Updates:**
   - Modify site config in Admin Config → Save
   - Navigate to User Config → Verify museum dropdown reflects changes
   - Navigate to Schedule → Verify site/museum options updated

2. **Museum Import Flow:**
   - Go to Admin Config → Import new museums → Save
   - Go to User Config → Verify new museums appear in dropdown immediately
   - Select a museum → Save user config
   - Go to Schedule → Verify selected museum persists

3. **Site Toggle:**
   - Admin Config → Toggle between SPL/KCLS
   - Verify base URL, endpoint, museums update for each site
   - Save → Verify activeSite persists

4. **Friendly Name Display:**
   - User Config → Open museum dropdown
   - Verify displays "Seattle Art Museum" not "seattle-art-museum"
   - Select museum → Save → Verify slug stored correctly

## Files Modified Summary

1. **ConfigManager.kt**
   - Added `configFlow: Flow<AppConfig>` for reactive updates

2. **AdminConfigViewModel.kt**
   - Changed to collect `configFlow` continuously

3. **UserConfigViewModel.kt**
   - Changed to collect `configFlow` continuously

4. **ScheduleViewModel.kt**
   - Changed to collect `configFlow` continuously

5. **AdminConfigScreen.kt**
   - Dynamic site list from config instead of hardcoded
   - Reactive museum updates on site change

6. **UserConfigScreen.kt**
   - Museum dropdown displays names, stores slugs
   - Continuous config observation

7. **ScheduleScreen.kt**
   - Museum dropdown displays names, stores slugs
   - Continuous config observation

## Design Principles Maintained

- **Centralization**: All config flows through ConfigManager → DataStore
- **Single Source of Truth**: DataStore is authoritative; Flows provide reactive access
- **Reactive UI**: StateFlow ensures automatic UI updates on data changes
- **Separation of Concerns**: ViewModels handle business logic, screens handle presentation
- **Lifecycle Awareness**: Flow collection respects coroutine scope lifecycle

## Next Steps

No further action required. All reported bugs have been fixed with a cohesive Flow-based architecture that ensures:
- Automatic cross-screen updates
- Proper museum name display
- Reactive site filtering
- Persistent configuration across navigation
