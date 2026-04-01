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

