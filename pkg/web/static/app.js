let ws;
let currentConfig = null;
let activeSite = 'spl';
let scheduledRunList = [];

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
        // Populate site and museum dropdowns for the schedule panel
        populateScheduleDropdowns();
    });
    document.getElementById('confirm-schedule').addEventListener('click', scheduleRun);
    document.getElementById('stop-btn').addEventListener('click', stopAgent);
    document.getElementById('restart-btn').addEventListener('click', restartAgent);

    startStatusPolling();
    connectWebSocket();
    startRunListPolling();
});

function switchSite(site) {
    activeSite = site;
    const splBtn = document.getElementById('btn-spl');
    const kclsBtn = document.getElementById('btn-kcls');
    if (site === 'spl') {
        splBtn.classList.add('active');
        kclsBtn.classList.remove('active');
    } else {
        kclsBtn.classList.add('active');
        splBtn.classList.remove('active');
    }

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

    renderMuseumPills();
}

// --- Status polling ---
function startStatusPolling() {
    setInterval(async () => {
        try {
            const res = await fetch('/api/status');
            const status = await res.json();
            const statusSpan = document.getElementById('status-text');
            const dropInfoSpan = document.getElementById('drop-time-info');
            if (status.running) {
                statusSpan.textContent = 'Status: Running';
                dropInfoSpan.textContent = '';
            } else {
                statusSpan.textContent = 'Status: Idle';
                dropInfoSpan.textContent = '';
            }
        } catch (err) {
            console.error('Status polling error:', err);
        }
    }, 2000);
}

// --- Run list polling ---
function startRunListPolling() {
    setInterval(async () => {
        await loadScheduledRuns();
    }, 5000);
}

async function loadScheduledRuns() {
    try {
        const res = await fetch('/api/runs');
        const runs = await res.json();
        scheduledRunList = runs;
        renderScheduledRuns();
    } catch (err) {
        console.error('Failed to load scheduled runs:', err);
    }
}

function renderScheduledRuns() {
    const container = document.getElementById('scheduled-runs-list');
    if (!container) return;
    if (scheduledRunList.length === 0) {
        container.innerHTML = '<p class="grey-text">No scheduled runs.</p>';
        return;
    }
    const ul = document.createElement('ul');
    ul.className = 'collection';
    for (const run of scheduledRunList) {
        const li = document.createElement('li');
        li.className = 'collection-item';
        const siteName = currentConfig?.Sites[run.site_key]?.Name || run.site_key;
        const museumName = currentConfig?.Sites[run.site_key]?.Museums[run.museum_slug]?.Name || run.museum_slug;
        const dropTime = new Date(run.drop_time);
        li.innerHTML = `
            <div class="row" style="margin-bottom:0;">
                <div class="col s8">
                    <strong>${siteName}</strong> - ${museumName}<br>
                    <span class="grey-text">${dropTime.toLocaleString()}</span><br>
                    <span class="grey-text">Mode: ${run.mode}</span>
                </div>
                <div class="col s4 right-align">
                    <button class="btn-flat red-text delete-run" data-id="${run.id}">Delete</button>
                </div>
            </div>
        `;
        ul.appendChild(li);
    }
    container.innerHTML = '';
    container.appendChild(ul);
    // Attach delete handlers
    document.querySelectorAll('.delete-run').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            const id = btn.dataset.id;
            const res = await fetch(`/api/runs/${id}`, { method: 'DELETE' });
            if (res.ok) {
                M.toast({html: 'Run deleted'});
                await loadScheduledRuns();
            } else {
                M.toast({html: 'Failed to delete run', classes: 'red'});
            }
        });
    });
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

        switchSite(activeSite);
        await loadScheduledRuns();
    } catch (err) {
        console.error(err);
        M.toast({html: 'Failed to load config', classes: 'red'});
    }
}

function populateGlobalSettings(cfg) {
    const splSite = cfg.Sites['spl'];
    if (splSite) {
        document.getElementById('base-url-spl').value = splSite.BaseURL;
        document.getElementById('availability-endpoint-spl').value = splSite.AvailabilityEndpoint;
        document.getElementById('digital-spl').checked = splSite.Digital;
        document.getElementById('physical-spl').checked = splSite.Physical;
        document.getElementById('location-spl').value = splSite.Location;
    }
    const kclsSite = cfg.Sites['kcls'];
    if (kclsSite) {
        document.getElementById('base-url-kcls').value = kclsSite.BaseURL;
        document.getElementById('availability-endpoint-kcls').value = kclsSite.AvailabilityEndpoint;
        document.getElementById('digital-kcls').checked = kclsSite.Digital;
        document.getElementById('physical-kcls').checked = kclsSite.Physical;
        document.getElementById('location-kcls').value = kclsSite.Location;
    }

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
    const splMuseums = sites['spl']?.Museums || {};
    const splLines = [];
    for (const [slug, m] of Object.entries(splMuseums)) {
        splLines.push(`${m.Name}:${slug}:${m.MuseumID}`);
    }
    document.getElementById('museums-list-spl').value = splLines.join('\n');

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
        await loadConfig();
    } else {
        const err = await res.json();
        M.toast({html: err.error || 'Error saving user settings', classes: 'red'});
    }
}

async function saveAdminConfig() {
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
        await loadScheduledRuns(); // refresh runs list
    } else {
        const err = await res.json();
        M.toast({html: err.error, classes: 'red'});
    }
}

function populateScheduleDropdowns() {
    const siteSelect = document.getElementById('schedule-site');
    const museumSelect = document.getElementById('schedule-museum');
    if (!siteSelect) {
        // Create the dropdowns if they don't exist
        const panel = document.getElementById('schedule-panel');
        const selectHtml = `
            <div class="row">
                <div class="input-field col s12">
                    <select id="schedule-site"></select>
                    <label>Site</label>
                </div>
                <div class="input-field col s12">
                    <select id="schedule-museum"></select>
                    <label>Museum</label>
                </div>
            </div>
        `;
        panel.insertAdjacentHTML('afterbegin', selectHtml);
        M.FormSelect.init(document.querySelectorAll('select'));
    }
    // Populate sites
    const siteSelectEl = document.getElementById('schedule-site');
    siteSelectEl.innerHTML = '';
    for (const [key, site] of Object.entries(currentConfig.Sites)) {
        const option = document.createElement('option');
        option.value = key;
        option.textContent = site.Name || key;
        siteSelectEl.appendChild(option);
    }
    M.FormSelect.init(siteSelectEl);
    siteSelectEl.addEventListener('change', () => populateMuseumsForSite(siteSelectEl.value));
    // Populate museums for the first site
    const firstSite = Object.keys(currentConfig.Sites)[0];
    if (firstSite) populateMuseumsForSite(firstSite);
}

function populateMuseumsForSite(siteKey) {
    const museumSelect = document.getElementById('schedule-museum');
    museumSelect.innerHTML = '';
    const site = currentConfig.Sites[siteKey];
    if (!site) return;
    for (const [slug, museum] of Object.entries(site.Museums)) {
        const option = document.createElement('option');
        option.value = slug;
        option.textContent = museum.Name || slug;
        museumSelect.appendChild(option);
    }
    M.FormSelect.init(museumSelect);
}

async function scheduleRun() {
    const siteKey = document.getElementById('schedule-site').value;
    const museumSlug = document.getElementById('schedule-museum').value;
    const dateTime = document.getElementById('scheduled-time').value;
    const timezone = document.getElementById('timezone').value;
    const mode = document.querySelector('input[name="mode"]:checked').value;

    if (!dateTime) {
        M.toast({html: 'Please select a date and time', classes: 'red'});
        return;
    }
    const payload = {
        siteKey,
        museumSlug,
        dropTime: dateTime,
        timezone,
        mode,
    };
    const res = await fetch('/api/schedule', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
    if (res.ok) {
        M.toast({html: 'Agent scheduled'});
        document.getElementById('schedule-panel').style.display = 'none';
        await loadScheduledRuns();
    } else {
        const err = await res.json();
        M.toast({html: err.error, classes: 'red'});
    }
}

async function stopAgent() {
    const res = await fetch('/api/stop', { method: 'POST' });
    const data = await res.json();
    M.toast({html: data.status});
    await loadScheduledRuns();
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

        if (event.data.includes('Agent starting')) {
            // Refresh runs list (the run will be removed automatically when finished)
            loadScheduledRuns();
        }
    };
    ws.onclose = () => {
        setTimeout(connectWebSocket, 3000);
    };
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}
