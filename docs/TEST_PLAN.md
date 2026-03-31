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
- **User Config**: Change all fields, save, reload, verify.
- **Admin Config**: Enter wrong PIN, correct PIN, edit site, add museum, save, verify.
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
| AC-10 | APK installs on Android 8+ device | Manual | |
