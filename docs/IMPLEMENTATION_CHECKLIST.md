**Purpose:** Step‑by‑step tasks for autonomous development.

```markdown
# Implementation Checklist

## Phase 1: Project Setup
- [ ] Create new Android project with minSdk 23, targetSdk 36, Kotlin.
- [ ] Add Jetpack Compose dependencies (Compose BOM, Material3, etc.).
- [ ] Add DataStore, WorkManager, AlarmManager permissions.
- [ ] Place `booking.aar` in `app/libs/` (initially placeholder; will be built later).
- [ ] Configure `build.gradle.kts` to include the AAR and necessary libraries, with NDK ABI filters for armeabi-v7a, arm64-v8a (ARM-only for mobile devices).
- [ ] Set up `AndroidManifest.xml` with permissions, foreground service, broadcast receivers.

## Phase 2: Data Layer
- [ ] Create `data` package.
- [ ] Implement `LogManager` singleton with `SharedFlow`, file writing.
- [ ] Implement `ConfigManager` with DataStore.
- [ ] Define data classes (`AppConfig`, `UserConfig`, `AdminConfig`, `SiteConfig`, `Museum`, `ScheduledRun`).
- [ ] Add `Defaults` object with hardcoded protected fields.
- [ ] Write `buildAgentConfig` function.

## Phase 3: Scheduling Layer
- [ ] Implement `AlarmScheduler`.
- [ ] Create `AlarmReceiver` (broadcast receiver).
- [ ] Create `BootReceiver` to re‑schedule after reboot.
- [ ] Register receivers in manifest.

## Phase 4: Foreground Service
- [ ] Create `BookingForegroundService` (extends `LifecycleService`).
- [ ] Create notification channel.
- [ ] Implement `onStartCommand` to launch Go agent with the run.
- [ ] Handle callbacks: logs → `LogManager`, status → update notification.

## Phase 5: Go Agent Bridge (in app)
- [ ] Create wrapper class `GoAgentWrapper` that manages the `MobileAgent` instance.
- [ ] Set up callbacks to forward logs to `LogManager`.
- [ ] Provide `startRun(run: ScheduledRun)` and `stopRun()` methods.

## Phase 6: ViewModels
- [ ] `DashboardViewModel`: expose `isRunning`, `status`, `countdown`, `startNow()`, `stop()`.
- [ ] `UserConfigViewModel`: load/save `UserConfig`.
- [ ] `AdminConfigViewModel`: load/save `AdminConfig` (PIN check).
- [ ] `ScheduleViewModel`: list runs, add/remove.
- [ ] `LogsViewModel`: expose log flow.

## Phase 7: UI (Compose)
- [ ] Implement `MainActivity` with `BottomNavigation` and `NavHost`.
- [ ] **DashboardScreen**:
  - [ ] Status card with Start Now / Stop buttons.
  - [ ] Countdown to next run.
  - [ ] Quick stats (active site, mode, preferred museum).
  - [ ] Log preview (last 3 entries).
- [ ] **UserConfigScreen**:
  - [ ] All fields as per spec.
  - [ ] Save button.
- [ ] **AdminConfigScreen**:
  - [ ] PIN dialog before entering.
  - [ ] Site toggle (SPL/KCLS).
  - [ ] Editable fields.
  - [ ] Museum list with add/edit/delete.
  - [ ] Save button.
- [ ] **ScheduleScreen**:
  - [ ] Dropdowns for site, museum, mode.
  - [ ] Date/time picker (must be future).
  - [ ] Schedule button.
  - [ ] List of scheduled runs with delete.
- [ ] **LogsScreen**:
  - [ ] Scrollable text area.
  - [ ] Auto‑scroll toggle.
  - [ ] Export button.
  - [ ] Clear button.

## Phase 8: Integration & Testing
- [ ] Connect ViewModels to UI.
- [ ] Test configuration saving/loading.
- [ ] Test scheduling (use short times, e.g., 1 minute in future).
- [ ] Test foreground service start/stop.
- [ ] Test log export.
- [ ] Test on real device (Android 6.0+).

## Phase 9: Build & Release
- [ ] Set up GitHub Actions workflow (`.github/workflows/build-and-release.yml`).
- [ ] Build AAR using `gomobile` (add script `scripts/build-go.sh`).
- [ ] Build signed APK.
- [ ] Create release with APK and AAR.

## Phase 10: Documentation
- [ ] Update `README.md` with instructions to build/run.
- [ ] Add screenshots to `docs/`.
- [ ] Final commit.
