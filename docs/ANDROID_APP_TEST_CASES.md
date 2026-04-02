# Comprehensive Test Cases for Android Wrapper of Go Booking Agent
*(Full 14‑Section Version – Aligned with Latest Technical Specification)*

This document provides exhaustive test cases for every component of the Android app, based on the latest technical specification (14 sections, including timezone handling, UI enhancements, config refresh, central data store, scheduling, alarms, and all other features). It serves as a validation checklist for an agentic AI.

---

## 1. Configuration Management (DataStore & Central Config)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **CONF‑01** | Fresh install – no config exists | App starts with default `AppConfig`. ConfigScreen loads default values (General tab: mode="alert", strikeTime="09:00", etc.; Sites tab: default SPL and KCLS sites). No errors. |
| **CONF‑02** | Save General settings | Change any field (e.g., mode to "booking", strikeTime to "10:00"), tap Save. Re‑open ConfigScreen; changes persisted. |
| **CONF‑03** | Save Admin settings (site fields) | Change base URL, endpoint, digital/physical, location, museums, credentials. Save. Re‑enter; changes reflected. |
| **CONF‑04** | Real‑time update across screens | Change active site dropdown in Sites tab → Dashboard Quick Stats updates immediately. Change preferred museum slug → Dashboard preferred museum name updates instantly. |
| **CONF‑05** | DataStore corruption handling | Manually corrupt `app_config` JSON (e.g., delete closing brace). App should fall back to default `AppConfig` without crash. |
| **CONF‑06** | Concurrency – rapid saves | Rapidly save multiple changes (e.g., change mode, then strike time, then preferred days). Final config should be consistent (no partial updates). |
| **CONF‑07** | Config refresh after external edit | If config file is edited externally while app is running, next read should reflect changes (`DataStore` updates automatically). |

---

## 2. UI Screens – Dashboard

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **DB‑01** | Initial load | Displays status "Idle", countdown to next scheduled run (or "No scheduled runs"), Quick Stats with active site display name (e.g., "SPL"), mode, preferred museum name. |
| **DB‑02** | Start Now button | Creates run with `dropTime` = current time + 30 seconds, using current general settings (mode, preferred museum, default credential, device timezone). Run appears in ScheduleScreen list; alarm scheduled. |
| **DB‑03** | Start Now – no preferred museum | Falls back to first museum of active site. |
| **DB‑04** | Start Now – no default credential | `credentialId = null`. JSON will have empty credentials (alert mode works; booking fails gracefully). |
| **DB‑05** | Stop button when run active | Calls `BookingForegroundService.stop()`. Service stops; `isRunning` false. Dashboard updates. |
| **DB‑06** | Countdown display | Shows time to next scheduled run (e.g., "2h 30m"). Updates every second. |
| **DB‑07** | Quick Stats updates in real time | Change active site in ConfigScreen → Dashboard updates immediately. Change mode → updates. |
| **DB‑08** | Click Start Now multiple times | Prevents duplicate runs? (Concurrency: if one already active, second should be ignored). |

---

## 3. UI Screens – ConfigScreen (PIN‑Protected)

### 3.1 General Tab
| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **GEN‑01** | PIN protection | Wrong PIN → dialog stays; correct PIN `1234` → shows General and Sites tabs. |
| **GEN‑02** | Mode selection | Switch Alert/Booking, save. Dashboard and Schedule default mode update. |
| **GEN‑03** | Strike Time picker | Open time picker, select time (e.g., "14:30"), save. Value stored as `HH:MM`. |
| **GEN‑04** | Preferred Days chips | Click to select/deselect days. List of day names saved correctly (e.g., `["Monday","Wednesday"]`). |
| **GEN‑05** | ntfy Topic field | Edit text, save. Persisted. |
| **GEN‑06** | Preferred Museum dropdown | Displays museum names from active site. Select a museum, save → slug stored. If museum removed later, dropdown clears on next load. |
| **GEN‑07** | Performance tuning fields | Edit numeric/duration fields (e.g., check window "45s", check interval "0.5s"). Save. Value stored. |
| **GEN‑08** | Validation – negative/zero values | Enter negative months (e.g., -1) → show error toast, do not save. |
| **GEN‑09** | Save button progress indicator | While saving, show `CircularProgressIndicator`; disable button. |

### 3.2 Sites Tab
| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SITE‑01** | Site dropdown | Shows site display names (e.g., "SPL", "KCLS") – not internal keys. Selecting changes displayed configuration. |
| **SITE‑02** | Visual cue header | Shows "Editing SPL Settings" (or similar) above fields. |
| **SITE‑03** | Edit site fields | Change base URL, endpoint, digital, physical, location values, save. Config updated. |
| **SITE‑04** | Add museum via dialog | Enter name, slug, museumId; save. Museum appears in list with format: `name (slug, museumId)`. |
| **SITE‑05** | Edit museum | Tap edit icon, change any field, save. List updates. |
| **SITE‑06** | Delete museum with confirmation | Tap delete → confirmation dialog "Delete 'Museum Name'?" → confirm → museum removed. If it was preferred museum in General tab, that field is cleared. |
| **SITE‑07** | Bulk import – valid lines | Paste lines `Name:slug:museumid`, parse preview, import. Museums added (replace duplicates by slug). |
| **SITE‑08** | Bulk import – invalid lines | Lines with wrong format (e.g., two colons only) are ignored; preview shows only valid ones. |
| **SITE‑09** | Bulk import – duplicate slug | Show overwrite confirmation dialog before replacing existing museum. |
| **SITE‑10** | Bulk import progress indicator | During import, show `CircularProgressIndicator`; disable import button until complete. |
| **SITE‑11** | Add credential | Fill label, username, password, email; save. Credential appears in list. |
| **SITE‑12** | Edit credential | Change fields, save. List updates. |
| **SITE‑13** | Delete credential with confirmation | Delete → confirmation → removed. If it was default, default cleared. |
| **SITE‑14** | Set default credential | Tap star icon on a credential. Only one star filled; `defaultCredentialId` set. |
| **SITE‑15** | Save admin config with progress | Tap Save → show progress indicator, disable button → on success, hide indicator. |
| **SITE‑16** | Switching sites without saving | Unsaved changes should be discarded when switching sites (no auto‑save). |

---

## 4. UI Screens – ScheduleScreen

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SCH‑01** | Site dropdown | Shows site display names (e.g., "SPL", "KCLS"). Changing resets museum and credential selections. |
| **SCH‑02** | Museum dropdown | Populated with museum names from selected site. Stores slug. |
| **SCH‑03** | Credential dropdown | Shows "Use default" plus all credential labels. Pre‑selects site’s default if exists, else "Use default". |
| **SCH‑04** | Mode dropdown | "Alert" / "Booking". Pre‑selected from `General.mode` but can be overridden. |
| **SCH‑05** | Timezone dropdown | Shows list of timezones with human‑readable names (e.g., "PST/PDT (Los Angeles)"). Stores IANA ID. Default = device timezone. |
| **SCH‑06** | Date/Time picker | Open dialog, select future date/time. Field shows formatted date/time in selected timezone. |
| **SCH‑07** | Schedule button – past date/time | Show toast error, do not create run. |
| **SCH‑08** | Schedule button – valid | Creates `ScheduledRun` with selected site, museum, credential, mode, UTC millis (converted from local time in selected timezone), and timezone ID. Adds to list, schedules alarm. |
| **SCH‑09** | Timezone conversion correctness | Pick a date/time in a different timezone (e.g., April 1, 2025, 09:00 Los Angeles). Verify `dropTimeMillis` is UTC equivalent. |
| **SCH‑10** | Scheduled runs list | Shows all runs sorted by `dropTimeMillis`. Each item shows site display name, museum name, mode, formatted local time according to run’s stored timezone, delete icon. |
| **SCH‑11** | Delete a run with confirmation | Tap delete → confirmation dialog → run removed from list, alarm cancelled. |
| **SCH‑12** | Multiple runs with different timezones | Allowed. Each run’s timezone stored and used for display. |
| **SCH‑13** | Edit scheduled run | Not required by spec; but if implemented, ensure timezone can be changed. |

---

## 5. Timezone Handling & Alarm Scheduling

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **TZ‑01** | Device timezone ≠ run timezone | Schedule run in `America/New_York` while device is in `America/Los_Angeles`. Alarm fires at correct UTC time (e.g., 12:00 PM New York time = 9:00 AM Los Angeles time). |
| **TZ‑02** | Daylight saving transition | Schedule run on a date that crosses DST (e.g., March 9, 2025, 02:30 `America/Los_Angeles` is invalid). App should show error or use next valid time. |
| **TZ‑03** | AlarmManager uses UTC | Alarm set with `dropTimeMillis` (UTC). Fires at exact UTC moment regardless of device timezone changes after scheduling. |
| **TZ‑04** | Boot recovery after reboot | After reboot, runs restored; alarms re‑scheduled using stored UTC millis. |
| **TZ‑05** | JSON dropTime format | `dropTime` in JSON is ISO‑8601 with Z suffix (UTC). Example: `2025-04-01T16:00:00Z`. |
| **TZ‑06** | JSON timezone field | Contains IANA ID (e.g., `"America/Los_Angeles"`). |
| **TZ‑07** | Timezone dropdown persistence | Selected timezone is stored in `ScheduledRun` and used when editing a run (if edit supported). |

---

## 6. Scheduled Runs – Trigger & Execution

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **RUN‑01** | Run triggers at exact time | Alarm fires (within 1 second); `BookingForegroundService` starts. |
| **RUN‑02** | Foreground service notification | Notification shows "Booking Bot" with current status (e.g., "Pre‑warming"). |
| **RUN‑03** | Service builds JSON at trigger time | Uses latest site settings, museums, credentials, and run’s stored timezone (not snapshot) at the time of trigger. |
| **RUN‑04** | JSON includes correct timezone field | `timezone` field equals run’s stored IANA ID. |
| **RUN‑05** | Agent starts and logs appear | Logs show "Pre‑warming", "Strike started", etc., in `LogsScreen`. |
| **RUN‑06** | Run finishes successfully | Agent stops; service removes run from list; logs show removal. |
| **RUN‑07** | Run fails (e.g., wrong credentials) | Agent logs error; service removes run; no crash. |
| **RUN‑08** | Run timeout | Check window expires; agent stops; service removes run. |
| **RUN‑09** | Concurrency handling | Second alarm fires while first is active → service logs warning and stops without starting agent. |
| **RUN‑10** | Missing references at trigger | `buildAgentConfig` returns null (e.g. site/museum deleted); service logs error, removes run, stops. |
| **RUN‑11** | Stop button in notification | Tap stop → service stops agent, removes run, stops service. |

---

## 7. JSON Serialization & Deserialization

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **JSON‑01** | `AppConfig` serialization | Save config → JSON string stored in `app_config` DataStore key. |
| **JSON‑02** | Deserialization on app start | Loads correctly; fallback to default if missing. |
| **JSON‑03** | `ScheduledRun` includes timezone | After serialization/deserialization, timezone field retains its value. |
| **JSON‑04** | `buildAgentConfig` output | Contains all required fields: `siteKey`, `museumSlug`, `dropTime` (ISO UTC), `mode`, `timezone`, `fullConfig`. |
| **JSON‑05** | Protected defaults in JSON | `bookinglinkselector`, `loginform` field names, etc., match Defaults constants, not user‑editable. |
| **JSON‑06** | Credential fallback | If credential missing, JSON uses empty strings for username/password/email. |
| **JSON‑07** | Timezone serialization round‑trip | Save a run with timezone `"Asia/Kolkata"`, restart app, load run → timezone still `"Asia/Kolkata"`. |

---

## 8. Logging & Debugging

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **LOG‑01** | Live logs appear in LogsScreen | Timestamp, level, message displayed. |
| **LOG‑02** | Export logs | Tap export → share sheet opens with log file containing all logs. |
| **LOG‑03** | Clear in‑memory logs | Clears screen display; file remains unchanged. |
| **LOG‑04** | Debug JSON viewer (optional) | Tap (e.g., long‑press logs title) → shows JSON for first pending run; copy button works. |
| **LOG‑05** | Auto‑scroll toggle | When enabled, new logs scroll to bottom. When disabled, user can scroll manually. |

---

## 9. First‑Run Wizard

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **WIZ‑01** | First launch after install | Wizard appears (not main UI). |
| **WIZ‑02** | Complete wizard steps | After finishing, config saved, flag set, main UI shown. |
| **WIZ‑03** | Second launch | Wizard does not appear; main UI loads directly. |
| **WIZ‑04** | Wizard – site selection | Allows choosing SPL or KCLS, edit fields. |
| **WIZ‑05** | Wizard – add museums | Bulk import or single add works. |
| **WIZ‑06** | Wizard – add credentials | At least one credential required. |
| **WIZ‑07** | Wizard – general settings | Mode, strike time, preferred days, ntfy topic, preferred museum are set. |
| **WIZ‑08** | Wizard – cancel | Cancelling should not save config; flag not set. |

---

## 10. Edge Cases & Stress Testing

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **EDGE‑01** | Add >100 museums | UI scrolls (`LazyColumn`) without lag. |
| **EDGE‑02** | Add >50 credentials | Scrollable list; performance OK. |
| **EDGE‑03** | Schedule >100 runs | List scrolls; alarms scheduled. |
| **EDGE‑04** | Run scheduled for past time | System clock moved back. At schedule time, validation prevents. If already in list, alarm may fire immediately; no crash. |
| **EDGE‑05** | Extreme power saving | Foreground service may be killed. After battery returns, no automatic recovery (acceptable for MVP). |
| **EDGE‑06** | Network loss during agent run | Logs network error; no crash. |
| **EDGE‑07** | Large log file (>10 MB) | Export still works. |
| **EDGE‑08** | Config update while run is active | Run uses config at trigger time; changes after trigger do not affect active run. |
| **EDGE‑09** | Delete museum referenced by run | At trigger, `buildAgentConfig` returns null; run removed gracefully. |
| **EDGE‑10** | Delete credential referenced by run | Same graceful failure. |
| **EDGE‑11** | Timezone with half‑hour offset | E.g. `Asia/Kolkata`. Conversion to UTC works correctly. |
| **EDGE‑12** | Date/time picker – leap year | February 29, 2024, selected → valid. |

---

## 11. Integration with Go Agent (Simulated)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **INT‑01** | Alert mode – no booking | Agent checks availability, sends ntfy notification (if new dates). Logs show "Alert sent". |
| **INT‑02** | Booking mode – correct credentials | Agent logs in, books, sends success notification. Run removed. |
| **INT‑03** | Booking mode – wrong credentials | Login fails; logs error; run removed. |
| **INT‑04** | Booking mode – spot already taken | Booking fails; logs "spot no longer available"; run removed. |
| **INT‑05** | Agent returns success | Service removes run. |
| **INT‑06** | Agent returns error | Service logs error, removes run. |
| **INT‑07** | Agent logs are parsed correctly | JSON log entries from Go agent are displayed with correct level (INFO, ERROR, etc.). |

---

## 12. Build & Release

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **REL‑01** | Build AAR with script | `scripts/build-go.sh` runs successfully, `libs/booking.aar` created. |
| **REL‑02** | Build APK | `./gradlew assembleRelease` succeeds. |
| **REL‑03** | APK installs on `arm64-v8a` device | Works. |
| **REL‑04** | APK installs on `armeabi-v7a` device | Works. |
| **REL‑05** | GitHub Actions workflow | Tag push builds AAR, APK, signs, creates release with assets. |
| **REL‑06** | ProGuard rules | No stripping of Go or serialization classes. Release mode runs properly. |

---

## 13. Performance & Stability

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **PERF‑01** | App cold start | < 3 seconds. |
| **PERF‑02** | Memory usage | Idle < 80 MB; during run < 120 MB. |
| **PERF‑03** | Battery drain | Not excessive; foreground service with wakelock only active during run. |
| **PERF‑04** | UI smoothness | Scrolling lists (museums, logs) maintain ~60 fps. |
| **PERF‑05** | Alarm precision | 99% of alarms fire within ±1 second of target. |

---

## 14. Real‑Time Updates & Central DataStore

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **RT‑01** | Config change updates other screens | Change active site in ConfigScreen → Dashboard Quick Stats updates without refresh. |
| **RT‑02** | Add museum in ConfigScreen | New museum appears in ScheduleScreen museum dropdown without restarting app. |
| **RT‑03** | Delete credential | Credential removed from ScheduleScreen dropdown immediately. |
| **RT‑04** | Change default credential | ScheduleScreen pre‑selection updates to the new default. |
| **RT‑05** | Change preferred museum slug | Dashboard preferred museum name changes instantly. |

---
## New/Updated test cases from bug fixes:

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SCH‑14** |	ScheduleScreen scrolls on small screen |	All fields (Site, Museum, Credential, Mode, Timezone, Date picker, Schedule button) are reachable by scrolling. |
| **GEN‑10**| 	Save General Settings – success feedback |	After tapping Save, a temporary message “Settings saved successfully” appears for ~2 seconds.|
| **SITE‑17** | 	Save Admin Settings – success feedback |	After tapping Save, a temporary message “Site configuration saved successfully” appears. |
| **DB‑09** |	Start Now – immediate logs |	Within 1 second of tapping Start Now, at least one log entry appears in LogsScreen (e.g., “App initialised” or “Service started”).| 
| **LOG‑08** |	LogManager initialisation log | 	On app start, a log “App initialised – log system ready” is visible in LogsScreen.|
| **LOG‑09** |	Go agent log callback works | 	During a run, logs from the Go agent appear in LogsScreen (e.g., “Pre‑warming...”, “Strike started”).|
| **TZ‑01 to TZ‑07**|	Timezone conversion tests | 	As originally defined – verify correct UTC conversion, DST handling, JSON fields, etc.|

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **LOG‑10** | Configuration loaded log | On app start, a log entry “Configuration loaded: activeSite=...” appears in LogsScreen. |
| **LOG‑11** | Schedule run – log added | After scheduling a run, a log “Scheduled run added: id=...” appears. |
| **LOG‑12** | Delete run – log added | After deleting a run, a log “Scheduled run removed (user delete): id=...” appears. |
| **LOG‑13** | Start Now – log added | After tapping Start Now, a log “Start Now run created: id=...” appears within 1 second. |
| **LOG‑14** | Foreground service start/stop logs | When a run triggers, logs “Foreground service started” and later “Foreground service stopped” appear. |
| **LOG‑15** | Agent start attempt log | Before agent runs, log “Attempting to start Go agent for run ...” appears. |
| **LOG‑16** | Alarm scheduled/cancelled logs | When scheduling a run, log “Alarm scheduled for run ...” appears; when deleting, “Alarm cancelled for run ...” appears. |
| **LOG‑17** | Boot receiver restore log | After device reboot, log “Boot receiver restoring X scheduled runs” appears. |
| **LOG‑18** | Config save logs | After saving General or Admin settings, log “General/Admin configuration saved: ...” appears. |
```
