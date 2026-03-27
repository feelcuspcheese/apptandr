let ws;
let currentConfig = null;
let activeSite = 'spl';
let scheduledRunTime = null;   // store scheduled drop time (Date)
let statusTimer = null;        // timer to check if run started

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
    const timepicker = document.getElementById('strike-time');
    M.Timepicker.init(timepicker, { twelveHour: false, defaultTime: '09:00' });

    // Initialize modal
    const modal = document.getElementById('admin-modal');
    if (modal) {
        M.Modal.init(modal);
        const tabs = document.querySelectorAll('.tabs');
        M.Tabs.init(tabs);
    }

    loadConfig();

    // Attach click handlers to day chips
    const dayChips = document.querySelectorAll('#days-pills .chip');
    dayChips.forEach(chip => {
        chip.addEventListener('click', (e) => {
            e.stopPropagation();
            chip.classList.toggle('active');
        });
    });

    // Site toggle buttons
    document.getElementById('btn-spl').addEventListener('click', () => switchSite('spl'));
    document.getElementById('btn-kcls').addEventListener('click', () => switchSite('kcls'));

    document.getElementById('save-user-config').addEventListener('click', saveUserConfig);
    document.getElementById('save-admin-config').addEventListener('click', saveAdminConfig);
    document.getElementById('run-now').addEventListener('click', runNow);
    document.getElementById('schedule').addEventListener('click', () => {
        document.getElementById('schedule-panel').style.display = 'block';
    });
    document.getElementById('confirm-schedule').addEventListener('click', schedule);
    document.getElementById('stop-btn').addEventListener('click', stopAgent);
    document.getElementById('restart-btn').addEventListener('click', restartAgent);

    startStatusPolling();
    connectWebSocket();
});

function switchSite(site) {
    activeSite = site;
    // Update button styles
    const splBtn = document.getElementById('btn-spl');
    const kclsBtn = document.getElementById('btn-kcls');
    if (site === 'spl') {
        splBtn.classList.add('active');
        kclsBtn.classList.remove('active');
    } else {
        kclsBtn.classList.add('active');
        splBtn.classList.remove('active');
    }

    // Update email requirement: SPL requires email, KCLS does not
    const emailField = document.getElementById('login-email');
    const emailContainer = document.getElementById('email-field-container');
    if (site === 'spl') {
        emailField.required = true;
        emailContainer.classList.remove('greyed-out');
        emailField.disabled = false;
    } else {
        emailField.required = false;
        emailContainer.classList.add('greyed-out');
        emailField.disabled = true;
    }

    // Re-render museum pills
    renderMuseumPills();
}

// --- Status polling (still needed for running state) ---
function startStatusPolling() {
    setInterval(async () => {
        try {
            const res = await fetch('/api/status');
            const status = await res.json();
            const statusSpan = document.getElementById('status-text');
            const dropInfoSpan = document.getElementById('drop-time-info');
            if (status.running) {
                // Agent is currently running – override any scheduled display
                statusSpan.textContent = 'Status: Running';
                if (status.dropTime) {
                    const dropDate = new Date(status.dropTime);
                    const localStr = dropDate.toLocaleString();
                    dropInfoSpan.textContent = `Started: ${localStr}`;
                } else {
                    dropInfoSpan.textContent = '';
                }
                // Clear any scheduled state
                scheduledRunTime = null;
                if (statusTimer) clearTimeout(statusTimer);
            } else {
                // Not running – check if we have a scheduled run
                if (scheduledRunTime && scheduledRunTime > new Date()) {
                    statusSpan.textContent = 'Status: Scheduled';
                    const localStr = scheduledRunTime.toLocaleString();
                    dropInfoSpan.textContent = `Scheduled: ${localStr}`;
                } else {
                    statusSpan.textContent = 'Status: Idle';
                    dropInfoSpan.textContent = '';
                }
            }
        } catch (err) {
            console.error('Status polling error:', err);
        }
    }, 2000);
}

// --- Config loading and populating ---
async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        currentConfig = await res.json();
        activeSite = currentConfig.ActiveSite || 'spl';
        populateGlobalSettings(currentConfig);
        populateMuseumsLists(currentConfig.Sites);
        updateDaysPills(currentConfig.PreferredDays);
        document.getElementById('strike-time').value = currentConfig.StrikeTime;
        const modeRadio = document.querySelector(`input[name="mode"][value="${currentConfig.Mode}"]`);
        if (modeRadio) modeRadio.checked = true;

        // Set active site toggle buttons
        switchSite(activeSite);
    } catch (err) {
        console.error(err);
        M.toast({html: 'Failed to load config', classes: 'red'});
    }
}

function populateGlobalSettings(cfg) {
    // SPL
    const splSite = cfg.Sites['spl'];
    if (splSite) {
        document.getElementById('base-url-spl').value = splSite.BaseURL;
        document.getElementById('availability-endpoint-spl').value = splSite.AvailabilityEndpoint;
        document.getElementById('digital-spl').checked = splSite.Digital;
        document.getElementById('physical-spl').checked = splSite.Physical;
        document.getElementById('location-spl').value = splSite.Location;
    }
    // KCLS
    const kclsSite = cfg.Sites['kcls'];
    if (kclsSite) {
        document.getElementById('base-url-kcls').value = kclsSite.BaseURL;
        document.getElementById('availability-endpoint-kcls').value = kclsSite.AvailabilityEndpoint;
        document.getElementById('digital-kcls').checked = kclsSite.Digital;
        document.getElementById('physical-kcls').checked = kclsSite.Physical;
        document.getElementById('location-kcls').value = kclsSite.Location;
    }

    // Global user settings (use any site's login form)
    const anySite = Object.values(cfg.Sites)[0];
    document.getElementById('login-username').value = anySite?.LoginForm?.Username || '';
    document.getElementById('login-password').value = anySite?.LoginForm?.Password || '';
    document.getElementById('login-email').value = anySite?.LoginForm?.Email || '';
    document.getElementById('ntfy-topic').value = cfg.NtfyTopic || 'myappointments';
    const checkIntervalSec = (cfg.CheckInterval / 1e9).toFixed(1);
    document.getElementById('check-interval').value = checkIntervalSec;
    const requestJitterSec = (cfg.RequestJitter / 1e9).toFixed(2);
    document.getElementById('request-jitter').value = requestJitterSec;
    const checkWindowMinutes = (cfg.CheckWindow / (60 * 1e9)).toFixed(2);
    document.getElementById('check-window').value = checkWindowMinutes;
    document.getElementById('months-to-check').value = cfg.MonthsToCheck || 2;
}

function populateMuseumsLists(sites) {
    // SPL
    const splMuseums = sites['spl']?.Museums || {};
    const splLines = [];
    for (const [slug, m] of Object.entries(splMuseums)) {
        splLines.push(`${m.Name}:${slug}:${m.MuseumID}`);
    }
    document.getElementById('museums-list-spl').value = splLines.join('\n');

    // KCLS
    const kclsMuseums = sites['kcls']?.Museums || {};
    const kclsLines = [];
    for (const [slug, m] of Object.entries(kclsMuseums)) {
        kclsLines.push(`${m.Name}:${slug}:${m.MuseumID}`);
    }
    document.getElementById('museums-list-kcls').value = kclsLines.join('\n');

    renderMuseumPills();
}

function updateDaysPills(preferredDays) {
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    days.forEach(day => {
        const chip = document.querySelector(`.chip[data-day="${day}"]`);
        if (chip) {
            if (preferredDays && preferredDays.includes(day)) {
                chip.classList.add('active');
            } else {
                chip.classList.remove('active');
            }
        }
    });
}

function parseMuseumsFromTextarea(siteKey) {
    const textareaId = siteKey === 'spl' ? 'museums-list-spl' : 'museums-list-kcls';
    const text = document.getElementById(textareaId).value;
    const lines = text.split(/\r?\n/);
    const museums = {};
    for (let line of lines) {
        line = line.trim();
        if (!line) continue;
        let parts = line.split(':');
        let name, slug, museumId;
        if (parts.length === 2) {
            slug = parts[0];
            museumId = parts[1];
            name = slug;
        } else if (parts.length === 3) {
            name = parts[0];
            slug = parts[1];
            museumId = parts[2];
        } else {
            console.warn('Invalid museum entry:', line);
            continue;
        }
        museums[slug] = { Name: name, Slug: slug, MuseumID: museumId };
    }
    return museums;
}

function renderMuseumPills() {
    const container = document.getElementById('museums-pills');
    container.innerHTML = '';
    const site = currentConfig?.Sites[activeSite];
    if (!site) return;

    const museums = site.Museums || {};
    const preferredSlug = site.PreferredSlug || '';
    for (const [slug, info] of Object.entries(museums)) {
        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = info.Name || slug;
        chip.dataset.slug = slug;
        if (preferredSlug === slug) {
            chip.classList.add('active');
        }
        chip.addEventListener('click', () => {
            document.querySelectorAll('#museums-pills .chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            // Update local preferred slug for the active site
            if (currentConfig && currentConfig.Sites[activeSite]) {
                currentConfig.Sites[activeSite].PreferredSlug = slug;
            }
        });
        container.appendChild(chip);
    }
}

function getPreferredDays() {
    return Array.from(document.querySelectorAll('#days-pills .chip.active')).map(c => c.dataset.day);
}

async function saveUserConfig() {
    const preferredDays = getPreferredDays();
    const mode = document.querySelector('input[name="mode"]:checked').value;
    const strikeTime = document.getElementById('strike-time').value;
    const checkWindowMinutes = parseFloat(document.getElementById('check-window').value);
    const checkWindow = checkWindowMinutes * 60 * 1e9;
    const checkIntervalSec = parseFloat(document.getElementById('check-interval').value);
    const checkInterval = checkIntervalSec * 1e9;
    const requestJitterSec = parseFloat(document.getElementById('request-jitter').value);
    const requestJitter = requestJitterSec * 1e9;
    const monthsToCheck = parseInt(document.getElementById('months-to-check').value) || 2;
    const ntfyTopic = document.getElementById('ntfy-topic').value;
    const loginUsername = document.getElementById('login-username').value;
    const loginPassword = document.getElementById('login-password').value;
    const loginEmail = document.getElementById('login-email').value;
    const preferredSlug = currentConfig?.Sites[activeSite]?.PreferredSlug || '';

    const payload = {
        activeSite,
        preferredSlug,
        mode,
        preferredDays,
        strikeTime,
        checkWindow,
        checkInterval,
        requestJitter,
        monthsToCheck,
        ntfyTopic,
        loginUsername,
        loginPassword,
        loginEmail,
    };

    const res = await fetch('/api/config/user', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
    if (res.ok) {
        M.toast({html: 'User settings saved'});
        await loadConfig(); // reload to reflect changes
    } else {
        const err = await res.json();
        M.toast({html: err.error || 'Error saving user settings', classes: 'red'});
    }
}

async function saveAdminConfig() {
    // Build SPL site config
    const splMuseums = parseMuseumsFromTextarea('spl');
    const splSite = {
        Name: "SPL",
        BaseURL: document.getElementById('base-url-spl').value,
        AvailabilityEndpoint: document.getElementById('availability-endpoint-spl').value,
        Digital: document.getElementById('digital-spl').checked,
        Physical: document.getElementById('physical-spl').checked,
        Location: document.getElementById('location-spl').value,
        BookingLinkSelector: 'a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available',
        LoginForm: {
            UsernameField: 'username',
            PasswordField: 'password',
            SubmitButton: 'submit',
            CSRFSelector: '',
            Username: '',
            Password: '',
            Email: '',
            AuthIDSelector: 'input[name="auth_id"]',
            LoginURLSelector: 'input[name="login_url"]',
        },
        BookingForm: {
            ActionURL: '',
            Fields: [],
            EmailField: 'email',
        },
        SuccessIndicator: 'Thank you!',
        Museums: splMuseums,
        PreferredSlug: currentConfig?.Sites['spl']?.PreferredSlug || '',
    };

    // Build KCLS site config
    const kclsMuseums = parseMuseumsFromTextarea('kcls');
    const kclsSite = {
        Name: "KCLS",
        BaseURL: document.getElementById('base-url-kcls').value,
        AvailabilityEndpoint: document.getElementById('availability-endpoint-kcls').value,
        Digital: document.getElementById('digital-kcls').checked,
        Physical: document.getElementById('physical-kcls').checked,
        Location: document.getElementById('location-kcls').value,
        BookingLinkSelector: 'a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available',
        LoginForm: {
            UsernameField: 'username',
            PasswordField: 'password',
            SubmitButton: 'submit',
            CSRFSelector: '',
            Username: '',
            Password: '',
            Email: '',
            AuthIDSelector: 'input[name="auth_id"]',
            LoginURLSelector: 'input[name="login_url"]',
        },
        BookingForm: {
            ActionURL: '',
            Fields: [],
            EmailField: 'email',
        },
        SuccessIndicator: 'Thank you!',
        Museums: kclsMuseums,
        PreferredSlug: currentConfig?.Sites['kcls']?.PreferredSlug || '',
    };

    const splPayload = { siteKey: 'spl', site: splSite };
    const kclsPayload = { siteKey: 'kcls', site: kclsSite };

    const splRes = await fetch('/api/config/admin', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(splPayload)
    });
    const kclsRes = await fetch('/api/config/admin', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(kclsPayload)
    });

    if (splRes.ok && kclsRes.ok) {
        M.toast({html: 'Admin settings saved'});
        await loadConfig();
    } else {
        M.toast({html: 'Error saving admin settings', classes: 'red'});
    }
}

async function runNow() {
    const res = await fetch('/api/run-now', { method: 'POST' });
    if (res.ok) {
        M.toast({html: 'Agent started, drop in 30 seconds'});
        // Clear any scheduled state because run now overrides
        scheduledRunTime = null;
        if (statusTimer) clearTimeout(statusTimer);
    } else {
        const err = await res.json();
        M.toast({html: err.error, classes: 'red'});
    }
}

async function schedule() {
    const dateTime = document.getElementById('scheduled-time').value;
    const timezone = document.getElementById('timezone').value;
    if (!dateTime) {
        M.toast({html: 'Please select a date and time', classes: 'red'});
        return;
    }
    const res = await fetch('/api/schedule', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dropTime: dateTime, timezone })
    });
    if (res.ok) {
        const data = await res.json();
        const dropTime = new Date(data.dropTime);
        scheduledRunTime = dropTime;
        M.toast({html: `Agent scheduled for ${dropTime.toLocaleString()}`});
        document.getElementById('schedule-panel').style.display = 'none';
        // Start a timer to check when the agent should start (for status display)
        if (statusTimer) clearTimeout(statusTimer);
        const now = new Date();
        const delay = dropTime - now;
        if (delay > 0) {
            statusTimer = setTimeout(() => {
                // The agent should start soon; we'll rely on status polling to catch it.
                // No action needed.
            }, delay);
        }
    } else {
        const err = await res.json();
        M.toast({html: err.error, classes: 'red'});
    }
}

async function stopAgent() {
    const res = await fetch('/api/stop', { method: 'POST' });
    const data = await res.json();
    M.toast({html: data.status});
    // Clear scheduled state because agent stopped
    scheduledRunTime = null;
    if (statusTimer) clearTimeout(statusTimer);
}

async function restartAgent() {
    await stopAgent();
    setTimeout(() => runNow(), 500);
}

function connectWebSocket() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${location.host}/api/logs`);
    ws.onmessage = (event) => {
        const logsDiv = document.getElementById('logs');
        const line = document.createElement('div');
        const timestamp = new Date().toLocaleTimeString();
        line.textContent = `[${timestamp}] ${event.data}`;
        logsDiv.appendChild(line);
        logsDiv.scrollTop = logsDiv.scrollHeight;

        // If we see "Agent starting", clear the scheduled state because it has started.
        if (event.data.includes('Agent starting')) {
            scheduledRunTime = null;
            if (statusTimer) clearTimeout(statusTimer);
        }
    };
    ws.onclose = () => {
        setTimeout(connectWebSocket, 3000);
    };
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}
