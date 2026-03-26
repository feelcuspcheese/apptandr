let ws;
let currentConfig = null;
let museumsMap = {}; // key: slug, value: { name, museum_id, pass_id? }

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
    const timepicker = document.getElementById('strike-time');
    M.Timepicker.init(timepicker, { twelveHour: false, defaultTime: '09:00' });

    loadConfig();

    // Attach click handlers to day chips (static)
    const dayChips = document.querySelectorAll('#days-pills .chip');
    dayChips.forEach(chip => {
        chip.addEventListener('click', (e) => {
            e.stopPropagation(); // avoid any interference
            chip.classList.toggle('active');
            // Optionally, you can store the selection in a variable; we'll read it when saving.
        });
    });

    document.getElementById('load-museums').addEventListener('click', parseMuseums);
    document.getElementById('save-config').addEventListener('click', saveConfig);
    document.getElementById('run-now').addEventListener('click', runNow);
    document.getElementById('schedule').addEventListener('click', () => {
        document.getElementById('schedule-panel').style.display = 'block';
    });
    document.getElementById('confirm-schedule').addEventListener('click', schedule);
    document.getElementById('stop-btn').addEventListener('click', stopAgent);

    connectWebSocket();
});

async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        currentConfig = await res.json();
        populateGlobalSettings(currentConfig);
        populateMuseumsList(currentConfig.Sites);
        updateDaysPills(currentConfig.PreferredDays);
        document.getElementById('strike-time').value = currentConfig.StrikeTime;
        document.getElementById('check-window').value = (currentConfig.CheckWindow / 60).toFixed(0);
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
    document.getElementById('check-interval').value = (cfg.CheckInterval / 1000000000).toFixed(0);
    document.getElementById('request-jitter').value = (cfg.RequestJitter / 1000000000).toFixed(1);
    document.getElementById('months-to-check').value = cfg.MonthsToCheck || 2;
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
    M.toast({html: `Loaded ${Object.keys(museumsMap).length} museums`});
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

async function saveConfig() {
    // Build Sites map from museumsMap and global settings
    const sites = {};
    for (const [slug, info] of Object.entries(museumsMap)) {
        const site = {
            Name: info.Name,
            BaseURL: document.getElementById('base-url').value,
            MuseumID: info.MuseumID,
            Slug: slug,
            PassID: '',
            Digital: document.getElementById('digital').checked,
            Physical: document.getElementById('physical').checked,
            Location: document.getElementById('location').value,
            AvailabilityEndpoint: document.getElementById('availability-endpoint').value,
            BookingLinkSelector: 'a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available',
            LoginForm: {
                UsernameField: 'username',
                PasswordField: 'password',
                SubmitButton: 'submit',
                CSRFSelector: '',
                Username: document.getElementById('login-username').value,
                Password: document.getElementById('login-password').value,
                Email: document.getElementById('login-email').value,
                AuthIDSelector: 'input[name="auth_id"]',
                LoginURLSelector: 'input[name="login_url"]',
            },
            BookingForm: {
                ActionURL: '',
                Fields: [],
                EmailField: 'email',
            },
            SuccessIndicator: 'Thank you!',
        };
        sites[slug] = site;
    }

    const preferredDays = getPreferredDays();
    const mode = document.querySelector('input[name="mode"]:checked').value;
    const strikeTime = document.getElementById('strike-time').value;
    const checkWindowMinutes = parseInt(document.getElementById('check-window').value);
    const checkWindow = checkWindowMinutes * 60;
    const checkInterval = parseInt(document.getElementById('check-interval').value) || 2;
    const requestJitter = parseFloat(document.getElementById('request-jitter').value) || 2;
    const monthsToCheck = parseInt(document.getElementById('months-to-check').value) || 2;

    const newConfig = {
        Sites: sites,
        PreferredSlug: currentConfig?.PreferredSlug || Object.keys(sites)[0] || '',
        Mode: mode,
        PreferredDays: preferredDays,
        StrikeTime: strikeTime,
        CheckWindow: checkWindow,
        CheckInterval: checkInterval,
        PreWarmOffset: 30,
        NtfyTopic: document.getElementById('ntfy-topic').value,
        MaxWorkers: 2,
        RequestJitter: requestJitter,
        MonthsToCheck: monthsToCheck,
    };

    const res = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newConfig)
    });
    if (res.ok) {
        M.toast({html: 'Configuration saved'});
        currentConfig = newConfig;
        renderMuseumPills();
    } else {
        const err = await res.json();
        M.toast({html: err.error || 'Error saving config', classes: 'red'});
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

function populateMuseumsList(sites) {
    const lines = [];
    for (const [slug, info] of Object.entries(sites)) {
        if (info.Name && info.Name !== slug) {
            lines.push(`${info.Name}:${slug}:${info.MuseumID}`);
        } else {
            lines.push(`${slug}:${info.MuseumID}`);
        }
    }
    document.getElementById('museums-list').value = lines.join('\n');
    museumsMap = {};
    for (const [slug, info] of Object.entries(sites)) {
        museumsMap[slug] = {
            Name: info.Name,
            Slug: slug,
            MuseumID: info.MuseumID,
        };
    }
    renderMuseumPills();
}
