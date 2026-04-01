# Bug Fix: Dashboard Stats and Museum Persistence

## Date: 2024-01-15

## Summary
Fixed multiple bugs related to dashboard stats not reflecting saved configuration, museum persistence issues between Admin Config and Schedule screens, and config key alignment with `default_config.yaml`.

## Issues Reported

1. **Dashboard Stats Showing Hardcoded Values**: Dashboard Quick Stats card displayed hardcoded "SPL", "Alert", "Seattle Art Museum" instead of loading from saved DataStore config.

2. **Museums Not Persisting to Schedule Screen**: Museums added in Admin Config for a site (e.g., SPL) were not appearing in the Schedule screen's Museum dropdown for that same site.

3. **Schedule Screen Museum Dropdown Not Updating**: When switching sites in Schedule screen, museum dropdown did not properly reload museums for the newly selected site.

4. **Config Key Alignment**: Need to verify Android app uses correct keys when retrieving/saving values from DataStore per Configuration Manager design.

## Root Causes Identified

### Issue 1: Dashboard Hardcoded Stats
**File**: `android-app/app/src/main/java/com/apptcheck/agent/ui/screens/DashboardScreen.kt`

**Problem**: Lines 197-199 had hardcoded TODO comments with static values:
```kotlin
Text("Active Site: SPL") // TODO: Load from config
Text("Mode: Alert") // TODO: Load from config
Text("Preferred Museum: Seattle Art Museum") // TODO: Load from config
```

**Fix**: Implemented reactive config loading using `LaunchedEffect`:
```kotlin
val configManager = remember { ConfigManager(application) }
var activeSite by remember { mutableStateOf("SPL") }
var mode by remember { mutableStateOf("Alert") }
var museum by remember { mutableStateOf("Not configured") }

LaunchedEffect(Unit) {
    try {
        val config = configManager.loadConfig()
        activeSite = config.admin.activeSite.uppercase()
        mode = config.user.mode.replaceFirstChar { it.uppercase() }
        
        val siteKey = config.admin.activeSite
        val preferredSlug = config.user.preferredSlug.ifEmpty {
            config.admin.sites[siteKey]?.museums?.keys?.firstOrNull() ?: ""
        }
        museum = config.admin.sites[siteKey]?.museums?.get(preferredSlug)?.name
            ?: preferredSlug.ifEmpty { "Not configured" }
    } catch (e: Exception) {
        // Keep defaults on error
    }
}
```

### Issue 2 & 3: Museum Parsing and Validation
**Files**: 
- `android-app/app/src/main/java/com/apptcheck/agent/data/ConfigManager.kt`
- `android-app/app/src/main/java/com/apptcheck/agent/viewmodel/ScheduleViewModel.kt`

**Problem A - ConfigManager.parseSiteConfig()**: Museum parsing logic was fragile and failed to correctly parse museums from JSON:
```kotlin
// Old approach - split by "}," which broke with nested JSON
val museumEntries = museumsJson.trim('{', '}').split("},\"").map { it.trim('}', '{') }
```

**Fix A**: Implemented robust regex-based museum parsing:
```kotlin
extractJsonObject(json, "museums")?.let { museumsJson ->
    if (museumsJson.isNotBlank() && museumsJson != "{}") {
        val museumPattern = "\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
        museumPattern.findAll(museumsJson).forEach { match ->
            try {
                val slug = match.groupValues[1]
                val museumData = match.groupValues[2]
                val name = extractString("{$museumData}", "name") ?: ""
                val museumId = extractString("{$museumData}", "museumId") ?: ""
                if (slug.isNotEmpty()) {
                    museums[slug] = Museum(name, slug, museumId)
                }
            } catch (e: Exception) {
                // Skip invalid museum entries
            }
        }
    }
}
```

**Problem B - ScheduleViewModel.onMuseumSelected()**: Museum validation didn't reload museums when validation failed, leading to stale UI state.

**Fix B**: Enhanced validation to reload museums on mismatch:
```kotlin
fun onMuseumSelected(museum: String) {
    viewModelScope.launch {
        try {
            val config = configManager.loadConfig()
            val currentSite = _uiState.value.selectedSite
            val museumsForSite = config.admin.sites[currentSite]?.museums?.keys?.toList() ?: emptyList()
            
            if (museum in museumsForSite) {
                _uiState.value = _uiState.value.copy(selectedMuseum = museum)
            } else {
                // Museum doesn't exist for this site - reload museums from config
                _uiState.value = _uiState.value.copy(
                    selectedMuseum = "",
                    availableMuseums = museumsForSite
                )
            }
        } catch (e: Exception) {
            // Keep current state on error
        }
    }
}
```

**Problem C - ScheduleViewModel.loadConfig()**: Didn't validate that `selectedSite` exists in loaded config before accessing museums.

**Fix C**: Added site validation:
```kotlin
val validCurrentSite = if (currentSite in availableSites) currentSite else availableSites.firstOrNull() ?: "spl"

val museums = config.admin.sites[validCurrentSite]?.museums?.keys?.toList() ?: emptyList()

_uiState.value = _uiState.value.copy(
    selectedSite = validCurrentSite,
    availableSites = availableSites,
    availableMuseums = museums,
    scheduledRuns = scheduledRuns
)
```

### Issue 4: Config Key Alignment
**Verified**: ConfigManager correctly handles key mapping:
- Kotlin data classes use camelCase (`activeSite`, `baseUrl`, `museumId`)
- JSON serialization preserves these keys
- `buildAgentConfig()` correctly maps to snake_case for Go agent compatibility (`active_site`, `baseurl`, `museumid`)
- All fields from `default_config.yaml` are present in Android config structure

## Files Modified

| File | Changes |
|------|---------|
| `DashboardScreen.kt` | Replaced hardcoded stats with reactive config loading |
| `ConfigManager.kt` | Fixed `parseSiteConfig()` museum parsing with regex |
| `ScheduleViewModel.kt` | Enhanced `onMuseumSelected()` validation and `loadConfig()` site validation |
| `TEST_PLAN.md` | Added AC-29 through AC-31 and TC-29 through TC-31 |

## Verification Steps

### TC-29: Dashboard Stats Reflect Saved Config
1. Configure custom museum in Admin Config for SPL
2. Save Admin Config
3. Navigate to Dashboard
4. Verify Quick Stats shows actual configured values

### TC-30: Museum Dropdown Updates on Site Change
1. Configure SPL with museums A, B
2. Configure KCLS with museum C
3. In Schedule screen, select SPL → verify museums A, B appear
4. Select KCLS → verify only museum C appears

### TC-31: Config Keys Alignment
1. Configure sites and save
2. Extract DataStore JSON
3. Verify structure matches `default_config.yaml` schema

## Centralization Compliance

✅ **Single Config Source**: All screens load from `ConfigManager.loadConfig()` → DataStore
✅ **Per-Site Isolation**: Museums stored per site in `sites[siteKey].museums` map
✅ **Reactive UI**: Uses `StateFlow` and `LaunchedEffect` for automatic updates
✅ **Schema Alignment**: JSON structure matches `default_config.yaml` as per TECHNICAL_SPEC.md section 3

## Impact Assessment

- **No Breaking Changes**: All fixes maintain backward compatibility
- **Improved UX**: Users see actual configured values instead of stale/hardcoded data
- **Data Integrity**: Museum validation prevents selecting non-existent museums
- **Maintainability**: Robust JSON parsing reduces future parsing errors

## Testing Recommendations

1. Test with fresh install (no existing DataStore)
2. Test with existing DataStore containing old format
3. Test adding/removing museums dynamically
4. Test site switching rapidly in Schedule screen
5. Verify logs show correct site/museum during agent execution
