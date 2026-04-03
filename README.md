# Appointment Agent – User Guide

## 1. Overview
The **Appointment Agent** is a Go‑based tool that monitors library passes (e.g., on `spl..com` and `rooms..org`) and either sends **ntfy** notifications when appointments become available or automatically books them based on your preferences. It runs as a Docker container and provides a modern, mobile‑friendly web dashboard for configuration and monitoring.

### Key Features
*   **Two sites:** Seattle Public Library (SPL) and King County Library System (KCLS).
*   **Multiple museums per site:** Define each museum with its unique ID.
*   **Alert mode:** Sends a ntfy notification with up to three action buttons (weekends prioritized) when new slots appear.
*   **Booking mode:** Automatically logs in and books the first available slot matching your preferred days.
*   **Scheduling:** Queue multiple runs with independent drop times, sites, museums, and modes.
*   **Stealth:** Realistic browser headers, random jitter, rest cycles, and microsecond‑precision drop time.
*   **Live logs:** WebSocket‑powered log viewer.
*   **Persistent configuration:** All settings saved in `config.yaml` (mounted volume).

---

## 2. Requirements
*   **Docker** (20.10 or later)
*   **A ntfy topic** (free account at [ntfy.sh](https://ntfy.sh) or self‑hosted)
*   **Library Credentials** (Library card number and PIN)

---

## 3. Quick Start

### 3.1 Pull the image (or build locally)
```bash
docker pull ghcr.io/yourusername/agent-dashboard:latest
```
If you built locally, use:
```bash
docker build -t agent-dashboard .
```

### 3.2 Run the container
```bash
docker run -d \
  -p 8080:8080 \
  -v $(pwd)/configs:/root/configs \
  --name agent-dashboard \
  agent-dashboard
```
*   `-p 8080:8080` – maps the web interface to port 8080 on your host.
*   `-v $(pwd)/configs:/root/configs` – mounts a local folder `configs` to store the persistent configuration. If the folder is empty, a default config will be copied automatically.

### 3.3 Access the dashboard
Open your browser at `http://localhost:8080`.

---

## 4. Configuration
All settings are stored in `config.yaml` inside the mounted folder. You can edit the file directly or use the dashboard.

### 4.1 Default configuration (auto‑created)
When you first start the container with an empty `configs` folder, the agent creates a default configuration with two empty sites (SPL and KCLS). You must fill in the details using the **Admin** modal.

### 4.2 Admin modal (mandatory first step)
Click the **⚙️ Admin** button in the top navigation bar. You will see two tabs: **SPL** and **KCLS**.

**For each site:**
1.  **Global Settings:**
    *   **Base URL:** e.g., `https://spl..com` (SPL) or `https://rooms..org` (KCLS).
    *   **Availability Endpoint:** `/pass/availability/institution` (usually stays as is).
    *   **Digital / Physical:** Check the type of passes available.
    *   **Location:** Usually `0`.
2.  **Museums:** Enter one museum per line in the format `Name:Slug:MuseumID`.
    *   *Name:* User‑friendly name (e.g., "Seattle Art Museum").
    *   *Slug:* Used in the URL (e.g., `SAM`). This is also the referer slug.
    *   *MuseumID:* The actual ID used in the availability endpoint (e.g., `7f2ac5c414b2`).
    *   *Example:* `Seattle Art Museum:SAM:7f2ac5c414b2`
3.  **Login credentials:**
    *   Login Username, Login Password, Email (email is required for SPL, optional for KCLS).

After filling, click **Save Admin Settings** for each tab.

### 4.3 Main dashboard settings
*   **Site toggle:** Use the SPL / KCLS buttons to switch between sites.
*   **Preferred Museum:** Click on a museum pill to select it for alerts or booking.
*   **Preferred Days:** Click on the day chips (e.g., Saturday, Sunday). Weekends are prioritized in notifications.
*   **Mode:** 
    *   *Alert Only:* Receive a ntfy notification with direct booking links.
    *   *Automatic Booking:* Agent attempts to book the first matching slot.
*   **Strike Time:** The time of day (24h format) for future daily scheduling.
*   **Check Window (minutes):** Duration to keep checking after drop time (e.g., 1 minute).
*   **Check Interval (seconds):** Time between checks. Random jitter is added automatically.
*   **Request Jitter (seconds):** Random delay before requests to mimic human behavior.
*   **Months to Check:** Number of months to fetch (Default: 2).
*   **ntfy Topic:** Your unique topic (e.g., `myappointments`).
*   **Credentials:** Shared across both sites (can be overridden in Admin modal).

---

## 5. Scheduling Runs
The agent does not run continuously; you schedule runs with a specific drop time.

### 5.1 Run Now
Click **▶ Run Now (in 30s)**. The agent starts a run immediately with a 30-second countdown. Useful for testing.

### 5.2 Schedule
Click **📅 Schedule** to open the panel:
1.  Select the **Site** and **Museum**.
2.  Choose the **Mode** (alert or booking).
3.  Pick a **Date & Time** and the appropriate **Timezone**.
4.  Click **Confirm Schedule**.

### 5.3 Pending runs
The list shows all future runs. You can delete a run by clicking the **trash icon**. Runs are executed sequentially as their drop times arrive.

### 5.4 How a run works
1.  **Pre-warm:** Starts at `drop time - 30s` to prepare the connection.
2.  **Strike:** At exact microsecond precision, it begins checking for availability.
3.  **Check:** Fetches calendar for the specified `months_to_check`.
4.  **Action:** 
    *   *Alert mode:* Sends ntfy notification and stops.
    *   *Booking mode:* Attempts to book the first matching day, notifies of result, and stops.
5.  **Timeout:** If no slots are found, it stops once the **Check Window** expires.

---

## 6. Live Logs
The right‑hand panel shows real‑time logs. Use this to monitor progress, see request details, and debug. Logs include microsecond-precision timestamps.

---

## 7. Notifications
*   **Alert notifications:** Title includes site/museum; message lists available dates with emojis. Includes up to three action buttons linking to the booking page.
*   **Booking notifications:** Reports success or failure of automatic attempts.

---

## 8. Stealth and Performance Features
*   **Random jitter:** Mimics human reaction time between requests.
*   **Rest cycle:** Pauses for a few seconds after a set number of checks (default 20) to avoid robotic patterns.
*   **Microsecond precision:** Spins for the last few milliseconds before the drop time to ensure the first request hits perfectly.
*   **Realistic headers:** Matches a real browser (`User-Agent`, `Referer`, `Accept`).
*   **Connection management:** Uses a cookie jar and keep-alive to maintain sessions.

---

## 9. Configuration File (`config.yaml`)
Example configuration:

```yaml
active_site: spl
check_interval: 2s
check_window: 1m
max_workers: 2
mode: alert
months_to_check: 2
ntfy_topic: myappointments
pre_warm_offset: 30s
preferred_days:
  - Saturday
  - Sunday
preferred_slug: ""
request_jitter: 2s
rest_cycle_checks: 20
rest_cycle_duration: 3s
scheduled_runs: []
strike_time: "09:00"
sites:
  spl:
    name: SPL
    baseurl: https://spl..com
    availabilityendpoint: /pass/availability/institution
    digital: true
    physical: false
    location: "0"
    bookinglinkselector: a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available
    loginform:
      usernamefield: username
      passwordfield: password
      submitbutton: submit
      csrfselector: ""
      username: "your_card_number"
      password: "your_pin"
      email: "your_email@example.com"
      authidselector: input[name="auth_id"]
      loginurlselector: input[name="login_url"]
    bookingform:
      actionurl: ""
      fields: []
      emailfield: email
    successindicator: Thank you!
    museums:
      SAM:
        name: Seattle Art Museum
        slug: SAM
        museumid: 7f2ac5c414b2
      Zoo:
        name: Woodland Park Zoo
        slug: Zoo
        museumid: 033bbf08993f
    preferredslug: SAM
  kcls:
    name: KCLS
    baseurl: https://rooms..org
    availabilityendpoint: /pass/availability/institution
    digital: true
    physical: false
    location: "0"
    bookinglinkselector: a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available
    loginform:
      usernamefield: username
      passwordfield: password
      submitbutton: submit
      csrfselector: ""
      username: "your_card_number"
      password: "your_pin"
      email: "your_email@example.com"
      authidselector: input[name="auth_id"]
      loginurlselector: input[name="login_url"]
    bookingform:
      actionurl: ""
      fields: []
      emailfield: email
    successindicator: Thank you!
    museums:
      KidsQuest:
        name: KidsQuest Children's Museum
        slug: KidsQuest
        museumid: 9ec25160a8a0
    preferredslug: KidsQuest
```

---

## 10. Troubleshooting
*   **Config not saved:** Ensure the `configs` folder has write permissions. Verify YAML syntax (indentation) if editing manually.
*   **Notifications not arriving:** Verify the ntfy topic name and ensure the container has internet access to `ntfy.sh`.
*   **Booking fails:** Double-check library credentials and museum IDs. Check logs to see if the museum offers digital vs. physical passes.
*   **Dropdowns empty:** You must save the **Admin** settings at least once for each site to populate the dashboard.
*   **Run timing:** Ensure your host system clock is synced and the correct timezone was selected during scheduling.

---

## 11. Building from Source (Optional)
```bash
git clone <repo_url>
cd agent
go mod download
go build -o agent ./cmd/agent
./agent --web
```

---

## 12. Support
For issues, please open an issue on GitHub. Include relevant log snippets and your configuration (redact sensitive credentials).

**Version:** 2.0 (final)  
**Last updated:** March 2026
