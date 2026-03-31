Purpose: High‑level scope, goals, and architecture.

# Android Wrapper for Appointment Agent – Overview

## 1. Goal
Build a native Android application that embeds the existing Go booking agent (from https://github.com/kiskey/apptcheck) as a shared library (AAR). The app provides a mobile‑first UI that replicates all functionality of the web dashboard, with native Android scheduling (AlarmManager) and centralised configuration and logging.

## 2. Scope
- ✅ Expose all user‑configurable fields (mode, strike time, preferred days, ntfy topic, performance tuning).
- ✅ Expose admin‑configurable fields (sites, museums, login credentials) – protected by a PIN.
- ✅ Hardcode protected fields (CSS selectors, form field names) – never shown in UI.
- ✅ Central configuration: single source of truth (DataStore) for all settings.
- ✅ Central logging: Go agent logs are captured and displayed in‑app, plus persisted to file.
- ✅ Scheduling: Use Android’s `AlarmManager` for exact‑time triggers; runs launched via foreground service.
- ✅ Background execution: Foreground service keeps the agent alive during the strike window.
- ✅ Notifications: The Go agent already sends ntfy notifications; no duplicate Android notifications needed, but a local notification for service status is required.
- ✅ Export logs: Share logs via system share sheet.
- ✅ Support both sites (SPL, KCLS) with per‑site museums and credentials.

## 3. Out of Scope
- No iOS version.
- No automatic updates of the Go agent (will be embedded at build time).
- No multi‑language support (English only).
- No offline mode.

## 4. Architecture Overview
┌─────────────────────────────────────────────────────────┐
│ Android App │
├─────────────────────────────────────────────────────────┤
│ UI (Jetpack Compose) │
│ ├── Dashboard Screen │
│ ├── User Config Screen │
│ ├── Admin Config Screen (PIN‑protected) │
│ ├── Schedule Screen │
│ └── Logs Screen │
├─────────────────────────────────────────────────────────┤
│ ViewModels (StateFlow) │
├─────────────────────────────────────────────────────────┤
│ Central Config Manager (DataStore) │
│ └── Hardcoded protected defaults │
├─────────────────────────────────────────────────────────┤
│ Central Log Manager (in‑memory buffer + file) │
├─────────────────────────────────────────────────────────┤
│ Go Agent Bridge (gomobile AAR) │
│ ├── Start(configJSON) │
│ ├── Stop() │
│ └── Callbacks (log, status) │
├─────────────────────────────────────────────────────────┤
│ Scheduling Layer │
│ ├── AlarmScheduler (exact alarms) │
│ ├── AlarmReceiver (broadcast receiver) │
│ └── BootReceiver (restart alarms after reboot) │
├─────────────────────────────────────────────────────────┤
│ Foreground Service (runs during strike) │
└─────────────────────────────────────────────────────────┘


## 5. Key Flows
- **Configuration:** User edits fields → saved to DataStore → UI reflects instantly.
- **Schedule Run:** User picks date/time → stored as `ScheduledRun` in DataStore → `AlarmManager` set.
- **Trigger Run:** Alarm fires → `AlarmReceiver` starts `ForegroundService` → Service builds JSON config from DataStore + run details → calls `MobileAgent.start()`.
- **Logging:** Go agent calls callback → `LogManager` adds entry (in‑memory + file) → UI updates via Flow.
- **Stop Run:** User taps Stop → Service calls `MobileAgent.stop()` → service stops itself.
