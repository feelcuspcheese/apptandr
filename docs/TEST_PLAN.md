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

