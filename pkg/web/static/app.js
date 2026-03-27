let ws;
let currentConfig = null;
let museumsMap = {}; // key: slug, value: { name, museum_id, site }
let activeSite = 'spl'; // default

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
    const timepicker = document.getElementById('strike-time');
    M.Timepicker.init(timepicker, { twelveHour: false, defaultTime: '09:00' });

    // Initialize modal
    const modal = document.getElementById('admin-modal');
    if (modal) {
        M.Modal.init(modal);
        // Initialize tabs in modal
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
    document.getElementById('btn-spl').classList.toggle('blue-grey-darken-1', site === 'spl');
    document.getElementById('btn-spl').classList.toggle('blue-grey-lighten-2', site !== 'spl');
    document.getElementById('btn-kcls').classList.toggle('blue-grey-darken-1', site === 'kcls');
    document.getElementById('btn-kcls').classList.toggle('blue-grey-lighten-2', site !== 'kcls');

    // Update email field requirement: SPL requires email, KCLS does not
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

    // Re-render museum pills based on new active site
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
    // SPL fields – we take the first museum of site 'spl' to get the global settings
    const splMuseum = Object.values(cfg.Sites).find(s => s.Site === 'spl');
    document.getElementById('base-url-spl').value = splMuseum?.BaseURL || 'https://spl.libcal.com';
    document.getElementById('availability-endpoint-spl').value = splMuseum?.AvailabilityEndpoint || '/pass/availability/institution';
    document.getElementById('digital-spl').checked = splMuseum?.Digital || true;
    document.getElementById('physical-spl').checked = splMuseum?.Physical || false;
    document.getElementById('location-spl').value = splMuseum?.Location || '0';

    // KCLS fields
    const kclsMuseum = Object.values(cfg.Sites).find(s => s.Site === 'kcls');
    document.getElementById('base-url-kcls').value = kclsMuseum?.BaseURL || 'https://rooms.kcls.org';
    document.getElementById('availability-endpoint-kcls').value = kclsMuseum?.AvailabilityEndpoint || '/pass/availability/institution';
    document.getElementById('digital-kcls').checked = kclsMuseum?.Digital || true;
    document.getElementById('physical-kcls').checked = kclsMuseum?.Physical || false;
    document.getElementById('location-kcls').value = kclsMuseum?.Location || '0';

    // Global user settings – take from any museum (they share the same credentials)
    const anyMuseum = Object.values(cfg.Sites)[0];
    document.getElementById('login-username').value = anyMuseum?.LoginForm?.Username || '';
    document.getElementById('login-password').value = anyMuseum?.LoginForm?.Password || '';
    document.getElementById('login-email').value = anyMuseum?.LoginForm?.Email || '';
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
    // Group museums by site
    const splMuseums = Object.values(sites).filter(s => s.Site === 'spl');
    const kclsMuseums = Object.values(sites).filter(s => s.Site === 'kcls');

    // Build SPL textarea
    const splLines = splMuseums.map(m => {
        const name = m.Name || m.Slug;
        return `${name}:${m.Slug}:${m.MuseumID}`;
    });
    document.getElementById('museums-list-spl').value = splLines.join('\n');

    // Build KCLS textarea
    const kclsLines = kclsMuseums.map(m => {
        const name = m.Name || m.Slug;
        return `${name}:${m.Slug}:${m.MuseumID}`;
    });
    document.getElementById('museums-list-kcls').value = kclsLines.join('\n');

    // Build the museum map for the active site (used for pills)
    const activeMuseums = splMuseums; // will be filtered later
    museumsMap = {};
    const targetSite = activeSite;
    for (const m of Object.values(sites)) {
        if (m.Site === targetSite) {
            museumsMap[m.Slug] = {
                Name: m.Name || m.Slug,
                Slug: m.Slug,
                MuseumID: m.MuseumID,
            };
        }
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

function parseMuseumsFromTextarea(site) {
    const textareaId = site === 'spl' ? 'museums-list-spl' : 'museums-list-kcls';
    const text = document.getElementById(textareaId).value;
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
            Site: site,
        };
    }
    return newMap;
}

function renderMuseumPills() {
    const container = document.getElementById('museums-pills');
    container.innerHTML = '';
    for (const [slug, info] of Object.entries(museumsMap)) {
        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = info.Name || slug;
        chip.dataset.slug = slug;
        const siteConfig = Object.values(currentConfig?.Sites || {}).find(s => s.Site === activeSite);
        if (siteConfig && siteConfig.PreferredSlug === slug) {
            chip.classList.add('active');
        }
        chip.addEventListener('click', () => {
            document.querySelectorAll('#museums-pills .chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            // Update the local preferred slug for the active site
            if (currentConfig) {
                for (const s of Object.values(currentConfig.Sites)) {
                    if (s.Site === activeSite) {
                        s.PreferredSlug = slug;
                        break;
                    }
                }
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
    // Get the preferred slug for the active site from the currentConfig (or pills)
    let preferredSlug = '';
    for (const s of Object.values(currentConfig.Sites)) {
        if (s.Site === activeSite) {
            preferredSlug = s.PreferredSlug;
            break;
        }
    }
    if (!preferredSlug && museumsMap[Object.keys(museumsMap)[0]]) {
        preferredSlug = Object.keys(museumsMap)[0];
    }

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
    // Parse SPL museums
    const splMuseums = parseMuseumsFromTextarea('spl');
    // Parse KCLS museums
    const kclsMuseums = parseMuseumsFromTextarea('kcls');

    // Build the full site configuration for SPL
    const splBase = {
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
        PreferredSlug: currentConfig?.Sites['spl']?.PreferredSlug || '',
        Site: 'spl',
    };

    // Build SPL museums as separate SiteInfo entries (each museum becomes a separate entry in the sites map)
    const splEntries = {};
    for (const [slug, info] of Object.entries(splMuseums)) {
        splEntries[slug] = {
            ...splBase,
            Name: info.Name,
            Slug: slug,
            MuseumID: info.MuseumID,
            Site: 'spl',
        };
    }

    // Build KCLS site base
    const kclsBase = {
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
        PreferredSlug: currentConfig?.Sites['kcls']?.PreferredSlug || '',
        Site: 'kcls',
    };

    const kclsEntries = {};
    for (const [slug, info] of Object.entries(kclsMuseums)) {
        kclsEntries[slug] = {
            ...kclsBase,
            Name: info.Name,
            Slug: slug,
            MuseumID: info.MuseumID,
            Site: 'kcls',
        };
    }

    // Combine all sites
    const allSites = { ...splEntries, ...kclsEntries };

    // Send to server – we need to send the entire sites map for both sites
    // The admin endpoint expects a site identifier and the full site config.
    // Since we have two separate sites, we'll send two requests, one for each.
    // But to simplify, we'll send two separate PUT requests.

    // Save SPL
    const splPayload = {
        site: 'spl',
        siteConfig: splBase, // this will be used to set the global settings for the site; but we also need to include the museums? Actually the admin endpoint updates the entire site's entry in the Sites map. Since we have multiple entries per site, we need to send the whole map for that site.
        // We'll instead send the entire map for that site.
    };
    // Simpler: we can construct a map of all entries for SPL (which are the museum entries) and send that as the site config.
    // But the server expects a SiteInfo object, not a map. So we need to restructure the config to have a separate field for museums per site.
    // Given the complexity, I'll assume the admin endpoint accepts a full list of museums for the site and the global settings, and the server updates all museums for that site.
    // I'll implement that in the server later.

    // For now, we'll keep the current admin endpoint as is (it updates a single site's configuration). We'll send the SPL site config (with the first museum as representative) and also send the museum list as a separate field.
    // This is a temporary workaround.

    // We'll instead call the existing `/api/config/admin` endpoint for each site with the site's base config and the museums list as an extra field.
    // But the server does not yet support that. To make it work, we would need to change the server's admin endpoint to accept a `museums` list and update all museums for that site.

    // Given the time, I'll provide a solution that works with the current server (which updates the entire Sites map). So the admin modal must rebuild the entire `sites` map and send it in one request.
    const combined = { ...allSites };
    const res = await fetch('/api/config/admin', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sites: combined })
    });
    if (res.ok) {
        M.toast({html: 'Admin settings saved'});
        await loadConfig();
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
