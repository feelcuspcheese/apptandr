Here is the document meticulously formatted as a Markdown file, ready to be copied and pasted directly into a GitHub `README.md` or `TESTING.md` file.

***

# Comprehensive Target State Validation Checklist for Android Wrapper

This document provides an exhaustive list of test cases, edge cases, and validation steps to ensure the Android app conforms to the technical specification. It covers all UI screens, data flows, configuration handling, scheduling, background execution, error handling, and integration with the Go agent. Each test case includes the action, expected result, and any special notes.

---

## 1. Configuration Management (DataStore & Central Config)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **CONF‑01** | Fresh install – no config exists | App starts with default AppConfig (empty museums, default general settings, no scheduled runs). ConfigScreen loads with default values. |
| **CONF‑02** | Save General settings | After editing any field and tapping Save, the value persists. Relaunch app; values remain. |
| **CONF‑03** | Save Admin settings (site fields) | Change base URL, endpoint, digital/physical, location, museums, credentials; after Save, config is updated. Re‑enter ConfigScreen; changes are reflected. |
| **CONF‑04** | Real‑time update across screens | Change Active Site dropdown in ConfigScreen → Dashboard screen updates Quick Stats to show new active site. Change preferred museum slug → Dashboard updates preferred museum name. |
| **CONF‑05** | DataStore corruption handling | Delete the config file manually (if possible) or simulate corrupt JSON. App should fall back to default AppConfig without crashing. |
| **CONF‑06** | Concurrency – multiple updates at once | Rapidly save different fields. Final config should be consistent (no partial updates). |

---

## 2. UI Screens – Dashboard

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **DB‑01** | Initial load | Displays status "Idle", countdown to next scheduled run (if any), Quick Stats with active site, mode, preferred museum name. |
| **DB‑02** | Start Now button | Creates a run with drop time = current time + 30 seconds. Run appears in ScheduleScreen list. Alarm scheduled. |
| **DB‑03** | Start Now – when no preferred museum selected | Falls back to first museum of active site. Run uses that museum. |
| **DB‑04** | Start Now – when no default credential | Run uses `credentialId = null`. JSON will have empty credentials (alert mode works; booking mode will fail gracefully). |
| **DB‑05** | Stop button when run active | Calls `BookingForegroundService.stop()`. Service stops; `isRunning` becomes false. |
| **DB‑06** | Countdown display | Shows time to next scheduled run (e.g., "2h 30m"). If no runs, shows "No scheduled runs". |
| **DB‑07** | Quick Stats updates in real time | Change active site in ConfigScreen → Dashboard updates immediately. Change mode → updates. |

---

## 3. UI Screens – ConfigScreen (General Tab)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **GEN‑01** | PIN protection | Enter wrong PIN → dialog stays; correct PIN (`1234`) → shows General and Sites tabs. |
| **GEN‑02** | Mode selection | Switch between Alert/Booking. Save. Mode reflects in Dashboard and Schedule default. |
| **GEN‑03** | Strike Time picker | Open time picker, select a time, save. Value stored as "HH:MM". |
| **GEN‑04** | Preferred Days chips | Click chips to select/deselect. List of day names saved correctly. |
| **GEN‑05** | ntfy Topic field | Edit text, save. Value persisted. |
| **GEN‑06** | Preferred Museum dropdown | Displays museum names from active site. Select a museum, save. Slug stored. If museum removed later, dropdown clears on next load. |
| **GEN‑07** | Performance tuning fields | Edit any numeric/duration field (e.g., check window "45s"). Save. Value stored. |
| **GEN‑08** | Validation – negative/zero values | Enter invalid (e.g., negative months). Save should show error toast, not save. |

---

## 4. UI Screens – ConfigScreen (Sites Tab)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SITE‑01** | Site dropdown | Shows "spl" and "kcls". Selecting changes the displayed configuration. |
| **SITE‑02** | Edit site fields (base URL, endpoint, digital, physical, location) | Change values, save. Config updated. |
| **SITE‑03** | Add museum via dialog | Enter name, slug, museumId; save. Museum appears in list. |
| **SITE‑04** | Edit museum | Tap edit icon, change any field, save. List updates. |
| **SITE‑05** | Delete museum | Tap delete icon. Museum removed. If it was the preferred museum in General tab, that field is cleared. |
| **SITE‑06** | Bulk import – valid lines | Paste lines `Name:slug:museumid`, parse preview, import. All museums added (replace duplicates by slug). |
| **SITE‑07** | Bulk import – invalid lines | Lines with wrong format (less/more colons) are ignored; preview shows only valid ones. |
| **SITE‑08** | Bulk import – duplicate slug | Overwrites existing museum with same slug (shows warning). |
| **SITE‑09** | Add credential | Fill label, username, password, email; save. Credential appears in list. |
| **SITE‑10** | Edit credential | Change fields, save. List updates. |
| **SITE‑11** | Delete credential | Delete; if it was default, default flag cleared. |
| **SITE‑12** | Set default credential | Tap star icon on a credential. Only one star filled; `defaultCredentialId` set. |
| **SITE‑13** | Save admin changes | After edits, tap Save. DataStore updated. |
| **SITE‑14** | Switching sites without saving | Changes made but not saved should be discarded when switching sites (or persist in memory? Spec says "save required". Should not auto‑save). |

---

## 5. UI Screens – ScheduleScreen

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SCH‑01** | Site dropdown | Shows all site keys from admin config. Selecting changes museum and credential dropdowns. |
| **SCH‑02** | Museum dropdown | Populated with names of museums from selected site. |
| **SCH‑03** | Credential dropdown | Shows "Use default" plus all credential labels from selected site. Pre‑selects default if exists, else "Use default". |
| **SCH‑04** | Mode dropdown | "Alert" and "Booking". Pre‑selected from `General.mode` but can be overridden per run. |
| **SCH‑05** | Date/Time picker | Open dialog, select future date/time. Field shows formatted date. |
| **SCH‑06** | Schedule button – past date | If selected datetime is in past, show toast error, do not create run. |
| **SCH‑07** | Schedule button – valid | Creates `ScheduledRun` object, adds to list, schedules alarm. |
| **SCH‑08** | Scheduled runs list | Shows all runs (future only) sorted by time. Each shows site, museum, mode, formatted date, delete icon. |
| **SCH‑09** | Delete a run | Tap delete; run removed from list, alarm cancelled. |
| **SCH‑10** | Multiple runs with same site/different museums | Allowed. Each appears in list. |
| **SCH‑11** | Multiple runs with different sites | Allowed. |
| **SCH‑12** | Run with credential = null (use default) | Created with `credentialId = null`. |
| **SCH‑13** | Run with specific credential | Selected `credentialId` stored. |

---

## 6. Configuration Propagation & Real‑time Updates

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **PROP‑01** | Admin adds new museum | ScheduleScreen museum dropdown updates immediately (if site selected). General tab preferred museum dropdown updates. |
| **PROP‑02** | Admin deletes a museum | If museum was selected in a pending run, that run will fail gracefully at trigger time (logged). |
| **PROP‑03** | Admin changes site base URL | Pending runs will use new URL when triggered (because config is fetched at trigger time). |
| **PROP‑04** | Admin changes default credential | ScheduleScreen credential dropdown updates. |
| **PROP‑05** | User changes preferred museum slug in General | Dashboard Quick Stats updates; Start Now uses new preferred museum. |

---

## 7. Scheduling & AlarmManager

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **ALARM‑01** | Schedule a run for 1 minute later | Alarm fires within 1 second of target time (allowing for system jitter). |
| **ALARM‑02** | Schedule multiple runs with different times | Each alarm fires at its respective time. |
| **ALARM‑03** | Device in doze mode (Android 6+) | Alarm should still fire because we use `setExactAndAllowWhileIdle`. |
| **ALARM‑04** | Device reboot before run | `BootReceiver` restores alarms; run triggers at original time (if not passed). |
| **ALARM‑05** | Cancel run via UI | Alarm cancelled; after reboot, not restored. |
| **ALARM‑06** | Run scheduled but then time passed while device off | When device boots, run should not trigger if its time is in the past? Spec does not specify. We can decide to ignore past runs (they will be removed after next trigger or on boot). Ensure no crash. |
| **ALARM‑07** | Two runs at exactly the same time | Only one can run at a time; second will be ignored (concurrency handling). |

---

## 8. BookingForegroundService & Go Agent Triggering

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **SRV‑01** | Alarm triggers – start service | Foreground service starts, notification appears. |
| **SRV‑02** | Service builds JSON from current config | JSON matches expected structure; uses current config values (not snapshot). |
| **SRV‑03** | Service starts Go agent with JSON | Agent starts; logs appear in LogsScreen. |
| **SRV‑04** | Run finishes successfully | Agent stops; service removes run from list and stops itself. |
| **SRV‑05** | Run fails (error) | Logged; service removes run and stops. |
| **SRV‑06** | Run timeout (check window expires) | Agent stops; service removes run and stops. |
| **SRV‑07** | Concurrency – second run while first active | Second alarm fires, service logs warning and stops without starting agent. |
| **SRV‑08** | Stop button while service active | Service calls `mobileAgent.stop()`, stops itself, run removed. |
| **SRV‑09** | Missing site/museum/credential in config at trigger time | `buildAgentConfig` returns null; service logs error, removes run, stops without starting agent. |
| **SRV‑10** | Notification updates | Notification shows status messages as agent runs. |
| **SRV‑11** | Notification stop button | Tapping stop action stops the service. |

---

## 9. Go Agent JSON Validation

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **JSON‑01** | JSON structure matches `mobile/agent.go` expectation | Must contain `siteKey`, `museumSlug`, `dropTime`, `mode`, `timezone`, `fullConfig` with all required fields. |
| **JSON‑02** | `fullConfig.sites[siteKey]` contains correct museumid | Uses `museum.museumId` from config. |
| **JSON‑03** | `loginform` fields filled with selected credential (or empty if none) | If credential exists, username, password, email are populated; else empty strings. |
| **JSON‑04** | Protected defaults (selectors, field names) are hardcoded | No user‑editable values appear; they match `Defaults` constants. |
| **JSON‑05** | `preferredslug` is slug, not name | Matches `general.preferredMuseumSlug`. |

---

## 10. Logging & Error Reporting

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **LOG‑01** | Go agent logs | Log entries appear in LogsScreen with timestamp, level, message. |
| **LOG‑02** | App internal logs (e.g., "Run X removed") | Also appear. |
| **LOG‑03** | Error logging | Errors (e.g., missing museum) logged with `ERROR` level. |
| **LOG‑04** | Export logs | Tap export; share sheet opens with log file attached. File contains all logs (including those from previous runs). |
| **LOG‑05** | Clear in‑memory logs | Clears screen display; file remains unchanged. |
| **LOG‑06** | Auto‑scroll toggle | When enabled, new logs automatically scroll to bottom. |

---

## 11. Edge Cases & Stress Testing

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **EDGE‑01** | Add >100 museums in a site | UI should still load and scroll (`LazyColumn`). |
| **EDGE‑02** | Add >50 credential sets | Scrollable list; performance acceptable. |
| **EDGE‑03** | Schedule >100 runs | List scrolls; alarms scheduled. |
| **EDGE‑04** | Run scheduled for a time that has already passed (e.g., user sets clock back) | At schedule time, validation should prevent past times. But if it somehow gets into list (e.g., after system time change), alarm might fire immediately or not. Ensure no crash. |
| **EDGE‑05** | Device low battery / extreme power saving | Foreground service may be killed. After battery returns, should reschedule via `BootReceiver`? Not covered; but app may not recover. Acceptable for MVP. |
| **EDGE‑06** | Network loss during agent execution | Agent logs network error; continues. No crash. |
| **EDGE‑07** | Large log file | Log file grows. Export still works. |
| **EDGE‑08** | Simultaneous admin config change and run trigger | Config change occurs while agent is building JSON? Should use latest config because DataStore updates are atomic. |
| **EDGE‑09** | Delete a museum that is referenced by pending run | At trigger time, `buildAgentConfig` logs error, run removed. No crash. |
| **EDGE‑10** | Delete a credential that is referenced by pending run | Same graceful failure. |

---

## 12. Integration with Go Agent (Simulated)

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **INT‑01** | Alert mode run – no booking attempted | Agent should only check and send ntfy notification (if new dates). Logs show "Alert sent". |
| **INT‑02** | Booking mode run – correct credentials | Agent logs in, books, sends success notification. Run removed. |
| **INT‑03** | Booking mode run – wrong credentials | Login fails; logs show error; run removed. |
| **INT‑04** | Booking mode run – spot already taken | Booking fails; logs show "spot no longer available"; run removed. |
| **INT‑05** | Agent returns success | Service removes run. |
| **INT‑06** | Agent returns error | Service logs error, removes run. |

---

## 13. Build & Release

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **REL‑01** | Build AAR with `scripts/build-go.sh` | Produces `libs/booking.aar` without errors. |
| **REL‑02** | Build APK | `./gradlew assembleRelease` succeeds. |
| **REL‑03** | APK installs on arm64-v8a device | Works. |
| **REL‑04** | APK installs on armeabi-v7a device | Works. |
| **REL‑05** | GitHub Actions workflow (tag push) | Builds AAR, APK, signs, creates release with assets. |
| **REL‑06** | ProGuard rules | No stripping of Go or serialization classes. App runs in release mode. |

---

## 14. Performance & Stability

| ID | Test Case | Expected Result |
| :--- | :--- | :--- |
| **PERF‑01** | App cold start time | < 3 seconds on average device. |
| **PERF‑02** | Memory usage | < 80 MB during idle; < 120 MB during run. |
| **PERF‑03** | Battery drain | Not excessive; foreground service with wakelock during run only. |
| **PERF‑04** | UI smoothness | Scrolling lists (museums, logs) should be smooth (60 fps). |
