Purpose: Test scenarios and acceptance tests.

# Test Plan

## 1. Unit Tests
- `ConfigManagerTest`: verify save/load, merging defaults.
- `LogManagerTest`: verify log buffering, file writing, export.
- `JSONBuilderTest`: ensure generated JSON matches expected structure.

## 2. Integration Tests
- **Configuration Persistence**: Change user/admin config, restart app, verify settings retained.
- **Scheduling**: Schedule a run 1 minute in the future, verify alarm fires and service starts.
- **Logging**: Force an error in Go agent (e.g., wrong credentials) and verify error appears in log screen.
- **Export**: Tap export, ensure file can be shared.

## 3. Manual UI Tests
- **Dashboard**: Start Now, verify countdown, logs appear, stop works.
- **User Config**: 
  - Change all fields, save, reload, verify.
  - Expand Performance Tuning section, verify all fields are visible without overlapping.
  - Scroll down when Performance Tuning is expanded, verify all fields are accessible.
  - Save configuration, verify visual feedback (success message) appears.
  - Navigate away and back, verify saved values persist.
- **Admin Config**: 
  - Enter wrong PIN, correct PIN, edit site, add museum, save, verify.
  - Scroll to bottom, verify login credentials fields are not cramped.
  - Verify Save button is fully visible and clickable.
  - Save configuration, verify visual feedback (success message) appears.
- **Schedule**: Add run, delete run, verify list updates.
- **Logs**: Auto‑scroll toggle, export, clear.

## 4. Acceptance Criteria (Pass/Fail)
| ID | Criteria | Test Method | Pass |
|----|----------|-------------|------|
| AC-1 | App launches without crash | Manual | |
| AC-2 | User can save user config and it persists after app restart | Manual | |
| AC-3 | Admin can save site config and it persists | Manual | |
| AC-4 | Scheduling a run triggers exact alarm | Manual | |
| AC-5 | Go agent starts and logs appear in app | Manual | |
| AC-6 | Booking mode (with correct credentials) sends ntfy notification | Manual | |
| AC-7 | Logs can be exported and shared | Manual | |
| AC-8 | App survives reboot and restores scheduled runs | Manual | |
| AC-9 | GitHub release produces signed APK | CI | |
| AC-10 | APK installs on Android 6.0+ (API 23) up to Android 16 and works correctly with ARM CPU architectures (armeabi-v7a, arm64-v8a). The APK must include properly aligned native libraries for ARM64 devices with 16KB page alignment as required by Android 16 | Manual | |
| AC-11 | User Config screen is scrollable when content overflows, Performance Tuning section expands without field overlap | Manual | |
| AC-12 | Admin Config screen is scrollable, all fields including login credentials are accessible, Save button is fully visible | Manual | |
| AC-13 | Save operations provide visual feedback (success/error message) to user | Manual | |
| AC-14 | Saved configuration values persist when navigating between screens | Manual | |
| AC-15 | Admin Config: Active Site dropdown shows SPL and KCLS options; selecting a site loads its specific config (base URL, endpoint, museums, etc.) | Manual | |
| AC-16 | Admin Config: Museums parsed from bulk import text field are saved per-site and persist after navigation | Manual | |
| AC-17 | Schedule Screen: Site dropdown shows both SPL and KCLS; Museum dropdown dynamically loads museums configured for the selected site | Manual | |
| AC-18 | Schedule Screen: Mode dropdown offers both "alert" and "booking" options | Manual | |
| AC-19 | Schedule Screen: Scheduled runs show correct site/museum combination and can be deleted | Manual | |
| AC-20 | Configuration Manager: Sites and their configs (including museums) are stored and retrieved per-site according to central structure | Manual | |
| AC-21 | Go Agent Integration: Clicking "Start Now" triggers actual Go agent (not simulation); logs show real execution without [SIMULATED] prefix | Manual | |
| AC-22 | Go Agent Logs: Logs screen displays actual Go agent output with proper log levels ([INFO], [ERROR], etc.) forwarded from MobileAgent | Manual | |
| AC-23 | Go Agent Config: Agent starts with correct site-specific configuration loaded from DataStore via ConfigManager.buildAgentConfig() | Manual | |
| AC-24 | Build Verification: App compiles successfully without Go AAR using stub MobileAgent implementation | Manual/CI | |
| AC-25 | Site Selector: Admin Config "Site" dropdown correctly labeled and shows SPL/KCLS options | Manual | |
| AC-26 | Museum Persistence: Museums configured in Admin Config appear in Schedule screen for selected site | Manual | |
| AC-27 | Mode Options: Schedule screen Mode dropdown offers both "alert" and "booking" values | Manual | |
| AC-28 | Config Structure: Android AppConfig JSON structure aligns with default_config.yaml schema | Manual | |
| AC-29 | Dashboard Stats: Quick Stats card loads active site, mode, and museum from saved config (not hardcoded) | Manual | |
| AC-30 | Museum Dropdown Refresh: Museum dropdown updates when site changes in Schedule screen | Manual | |
| AC-31 | Config Keys: DataStore JSON keys correctly map to/from default_config.yaml fields | Manual | |

## 5. Go Agent Integration Tests

### TC-21: Go Agent Execution Verification
**Preconditions**: 
- Go AAR (booking.aar) built and included in libs/
- Admin config has valid site credentials configured

**Steps**:
1. Navigate to Dashboard screen
2. Click "Start Now" button
3. Wait 30 seconds for agent to start
4. Navigate to Logs screen
5. Observe log entries

**Expected Results**:
- Log shows "[REAL] Go agent started in <mode> mode" (not [SIMULATED])
- Subsequent logs show actual Go agent execution with levels like "[INFO]", "[ERROR]"
- Logs include museum check progress, availability results, notification status
- No ClassNotFoundException or similar errors

**Pass Criteria**: All expected log entries appear without simulation markers

### TC-22: Go Agent Callback Verification
**Preconditions**: Go agent running from Dashboard

**Steps**:
1. Start agent from Dashboard
2. While running, navigate to Logs screen
3. Observe real-time log updates
4. Wait for agent to complete or click Stop

**Expected Results**:
- Logs appear in real-time as Go agent executes
- Status callback updates UI notification text
- On completion, log shows "Go agent status: finished" or "stopped"
- Service stops cleanly after completion

**Pass Criteria**: Callbacks properly forward Go logs and status to Android UI

### TC-23: Site-Specific Config Verification
**Preconditions**: Both SPL and KCLS sites configured with different museums

**Steps**:
1. In Admin Config, set active site to SPL, configure museums
2. Save and return to Dashboard
3. Start agent, verify logs show SPL museum
4. Change active site to KCLS in Admin Config
5. Start agent again, verify logs show KCLS museum

**Expected Results**:
- Each run uses correct site's configuration
- Museum slug and ID match selected site
- Base URL and endpoint reflect chosen site

**Pass Criteria**: ConfigManager correctly builds per-site config JSON

## 6. Build and Configuration Tests

### TC-24: MobileAgent Stub Compilation
**Preconditions**: Go AAR not available (development/CI environment)

**Steps**:
1. Run `./gradlew assembleDebug` or `./gradlew assembleRelease`
2. Check build output for compilation errors

**Expected Results**:
- No "Unresolved reference: mobile.MobileAgent" errors
- No "Cannot infer a type" errors for callback parameters
- Build completes successfully
- APK generated without crashes

**Pass Criteria**: Build succeeds with stub MobileAgent implementation

### TC-25: Site Selector Label Verification
**Preconditions**: App installed and running

**Steps**:
1. Navigate to Admin Config screen (PIN: 1234)
2. Observe the site selection dropdown label

**Expected Results**:
- Label reads "Site" (not "Active Site")
- Dropdown shows both SPL and KCLS options
- Selecting an option changes the displayed value

**Pass Criteria**: UI label matches user expectations

### TC-26: Cross-Screen Museum Visibility
**Preconditions**: Admin has configured museums for at least one site

**Steps**:
1. In Admin Config, select site SPL
2. Add museum in format: `Test Museum:test-museum:test123`
3. Click "Parse Museums", verify success
4. Click "Save Admin Configuration"
5. Navigate to Schedule screen
6. Select SPL in Site dropdown
7. Open Museum dropdown

**Expected Results**:
- Museum parsed successfully in Admin Config
- Save operation completes without error
- Museum appears in Schedule screen dropdown for SPL
- Museum does NOT appear when KCLS is selected (if not configured for KCLS)

**Pass Criteria**: Museums persist across screens via ConfigManager

### TC-27: Mode Dropdown Options
**Preconditions**: App installed and running

**Steps**:
1. Navigate to Schedule screen
2. Open Mode dropdown

**Expected Results**:
- Dropdown shows "alert" option
- Dropdown shows "booking" option
- Both options are selectable
- Selected mode persists when scheduling run

**Pass Criteria**: Mode dropdown implements both values per default_config.yaml

### TC-28: Configuration Structure Alignment
**Preconditions**: Both SPL and KCLS sites configured

**Steps**:
1. Configure SPL with custom Base URL and museums
2. Configure KCLS with different Base URL and museums
3. Save Admin Config
4. Inspect DataStore contents (via adb or debug tool)

**Expected Results**:
- JSON structure matches:
  ```json
  {
    "admin": {
      "activeSite": "spl",
      "sites": {
        "spl": { "baseUrl": "...", "museums": {...} },
        "kcls": { "baseUrl": "...", "museums": {...} }
      }
    }
  }
  ```
- Structure aligns with default_config.yaml schema
- Field names use camelCase (Kotlin convention)
- All YAML fields present in Android config

**Pass Criteria**: ConfigManager serialization matches design spec

### TC-29: Dashboard Stats Reflect Saved Config
**Preconditions**: 
- Admin has configured a custom museum for SPL site in Admin Config
- User has set preferredSlug or museum is configured

**Steps**:
1. Navigate to Admin Config, select SPL site
2. Add a new museum (e.g., "Test Museum:test-museum:test123")
3. Save Admin Config
4. Navigate to Dashboard screen
5. Observe Quick Stats card

**Expected Results**:
- Active Site shows "SPL" (not hardcoded)
- Mode shows current user mode (e.g., "Alert" or "Booking")
- Preferred Museum shows the name of the first configured museum or preferredSlug museum
- Stats update when config changes

**Pass Criteria**: Dashboard loads stats from DataStore via ConfigManager, not hardcoded values

### TC-30: Schedule Screen Museum Dropdown Updates on Site Change
**Preconditions**: 
- SPL has museums: "seattle-art-museum", "zoo"
- KCLS has museum: "kidsquest"

**Steps**:
1. Navigate to Schedule screen
2. Select "SPL" from Site dropdown
3. Open Museum dropdown - verify SPL museums appear
4. Select "KCLS" from Site dropdown
5. Open Museum dropdown again

**Expected Results**:
- After selecting SPL: Museum dropdown shows "seattle-art-museum", "zoo"
- After selecting KCLS: Museum dropdown shows only "kidsquest"
- Museum selection resets when site changes
- No stale museums from previous site selection

**Pass Criteria**: Museum dropdown dynamically reloads from saved config per selected site

### TC-31: Config Keys Alignment with default_config.yaml
**Preconditions**: App installed with fresh DataStore

**Steps**:
1. Configure sites and museums in Admin Config
2. Save configuration
3. Extract DataStore JSON (via adb backup or debug logging)
4. Compare field names with default_config.yaml

**Expected Results**:
- Android uses correct keys matching YAML structure:
  - `activeSite` ↔ `active_site`
  - `sites.spl.baseUrl` ↔ `sites.spl.baseurl`
  - `sites.spl.museums` ↔ `sites.spl.museums`
  - `user.mode` ↔ `mode`
  - `scheduledRuns[].siteKey` ↔ used in run context
- Serialization/deserialization preserves all fields
- No data loss during save/load cycles

**Pass Criteria**: ConfigManager correctly maps between Kotlin camelCase and JSON/YAML snake_case where applicable

