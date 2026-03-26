let ws;
let currentConfig = null;
let museumsMap = {}; // key: slug, value: { name, museum_id }

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
    const timepicker = document.getElementById('strike-time');
    M.Timepicker.init(timepicker, { twelveHour: false, defaultTime: '09:00' });

    // Initialize modal
    const modal = document.getElementById('admin-modal');
    if (modal) {
        M.Modal.init(modal);
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

    document.getElementById('load-museums').addEventListener('click', parseMuseums);
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
                if (status.dropTime) {
                    const dropDate = new Date(status.dropTime);
                    const localStr = dropDate.toLocaleString();
                    dropInfoSpan.textContent = `Scheduled: ${localStr}`;
                } else {
                    dropInfoSpan.textContent = '';
                }
            } else {
                statusSpan.textContent = 'Status: Idle';
                dropInfoSpan.textContent = '';
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
        populateGlobalSettings(currentConfig);
        populateMuseumsList(currentConfig.Sites);
        updateDaysPills(currentConfig.PreferredDays);
        document.getElementById('strike-time').value = currentConfig.StrikeTime;
        const modeRadio = document.querySelector(`input[name="mode"][value="${currentConfig.Mode}"]`);
        if (modeRadio) modeRadio.checked = true;
    } catch (err) {
        console.error(err);
        M.toast({html: 'Failed to load config', classes: 'red'});
    }
}

function populateGlobalSettings(cfg) {
    const firstSite = Object.values(cfg.Sites)[0];
    document.getElementById('base-url').value = firstSite?.BaseURL || 'https://spl.libcal.com';
    document.getElementById('availability-endpoint').value = firstSite?.AvailabilityEndpoint || '/pass/availability/institution';
    document.getElementById('digital').checked = firstSite?.Digital || true;
    document.getElementById('physical').checked = firstSite?.Physical || false;
    document.getElementById('location').value = firstSite?.Location || '0';
    document.getElementById('login-username').value = firstSite?.LoginForm?.Username || '';
    document.getElementById('login-password').value = firstSite?.LoginForm?.Password || '';
    document.getElementById('login-email').value = firstSite?.LoginForm?.Email || '';
    document.getElementById('ntfy-topic').value = cfg.NtfyTopic || 'myappointments';
    const checkIntervalSec = (cfg.CheckInterval / 1e9).toFixed(1);
    document.getElementById('check-interval').value = checkIntervalSec;
    const requestJitterSec = (cfg.RequestJitter / 1e9).toFixed(2);
    document.getElementById('request-jitter').value = requestJitterSec;
    const checkWindowMinutes = (cfg.CheckWindow / (60 * 1e9)).toFixed(2);
    document.getElementById('check-window').value = checkWindowMinutes;
    document.getElementById('months-to-check').value = cfg.MonthsToCheck || 2;
}

function populateMuseumsList(sites) {
    const lines = [];
    for (const [slug, info] of Object.entries(sites)) {
        const name = info.Name || slug;
        lines.push(`${name}:${slug}:${info.MuseumID}`);
    }
    document.getElementById('museums-list').value = lines.join('\n');
    museumsMap = {};
    for (const [slug, info] of Object.entries(sites)) {
        museumsMap[slug] = {
            Name: info.Name || slug,
            Slug: slug,
            MuseumID: info.MuseumID,
        };
    }
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

function parseMuseums() {
    const text = document.getElementById('museums-list').value;
    const lines = text.split(/\r?\n/);
    const newMap = {};
    for (let line of lines) {
        line = line.trim();
        if (!line) continue;
        let parts = line.split(':');
        let slug, museumId, name;
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
        newMap[slug] = {
            Name: name,
            Slug: slug,
            MuseumID: museumId,
        };
    }
    museumsMap = newMap;
    renderMuseumPills();
    M.toast({html: `Loaded ${Object.keys(museumsMap).length} museums (preview only, not saved yet)`});
}

function renderMuseumPills() {
    const container = document.getElementById('museums-pills');
    container.innerHTML = '';
    for (const [slug, info] of Object.entries(museumsMap)) {
        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = info.Name || slug;
        chip.dataset.slug = slug;
        if (currentConfig && currentConfig.PreferredSlug === slug) {
            chip.classList.add('active');
        }
        chip.addEventListener('click', () => {
            document.querySelectorAll('#museums-pills .chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            currentConfig.PreferredSlug = slug;
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
    const preferredSlug = currentConfig?.PreferredSlug || Object.keys(museumsMap)[0] || '';

    const payload = {
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
        // Reload config to reflect any changes (though only user fields changed)
        await loadConfig();
    } else {
        const err = await res.json();
        M.toast({html: err.error || 'Error saving user settings', classes: 'red'});
    }
}

async function saveAdminConfig() {
    const baseUrl = document.getElementById('base-url').value;
    const availabilityEndpoint = document.getElementById('availability-endpoint').value;
    const digital = document.getElementById('digital').checked;
    const physical = document.getElementById('physical').checked;
    const location = document.getElementById('location').value;

    // Build sites from current museumsMap
    const sites = {};
    for (const [slug, info] of Object.entries(museumsMap)) {
        sites[slug] = {
            Name: info.Name,
            Slug: slug,
            MuseumID: info.MuseumID,
            // other fields will be populated from baseUrl, etc. on server side
        };
    }

    const payload = {
        sites,
        baseUrl,
        availabilityEndpoint,
        digital,
        physical,
        location,
    };

    const res = await fetch('/api/config/admin', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
    if (res.ok) {
        M.toast({html: 'Admin settings saved'});
        await loadConfig(); // reload to reflect changes
    } else {
        const err = await res.json();
        M.toast({html: err.error || 'Error saving admin settings', classes: 'red'});
    }
}

async function runNow() {
    const res = await fetch('/api/run-now', { method: 'POST' });
    if (res.ok) {
        M.toast({html: 'Agent started, drop in 30 seconds'});
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
        M.toast({html: 'Agent scheduled'});
        document.getElementById('schedule-panel').style.display = 'none';
    } else {
        const err = await res.json();
        M.toast({html: err.error, classes: 'red'});
    }
}

async function stopAgent() {
    const res = await fetch('/api/stop', { method: 'POST' });
    const data = await res.json();
    M.toast({html: data.status});
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
    };
    ws.onclose = () => {
        setTimeout(connectWebSocket, 3000);
    };
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}
