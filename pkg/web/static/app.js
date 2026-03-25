let ws;
let currentConfig = null;

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
    // Initialize timepicker
    const timepicker = document.getElementById('strike-time');
    M.Timepicker.init(timepicker, { twelveHour: false, defaultTime: '09:00' });

    loadConfig();

    document.getElementById('config-form').addEventListener('submit', saveConfig);
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
        document.getElementById('config-loading').style.display = 'none';
        document.getElementById('strike-time').value = currentConfig.StrikeTime;
        document.getElementById('check-window').value = (currentConfig.CheckWindow / 60).toFixed(0);
        const modeRadio = document.querySelector(`input[name="mode"][value="${currentConfig.Mode}"]`);
        if (modeRadio) modeRadio.checked = true;

        // Populate museums pills
        const pillsDiv = document.getElementById('museums-pills');
        pillsDiv.innerHTML = '';
        for (const [slug, info] of Object.entries(currentConfig.Sites)) {
            const chip = document.createElement('div');
            chip.className = 'chip';
            chip.textContent = info.Name || slug;
            chip.dataset.slug = slug;
            if (currentConfig.PreferredSlug === slug) {
                chip.classList.add('active');
            }
            chip.addEventListener('click', (e) => {
                e.preventDefault();
                // Remove active class from all
                document.querySelectorAll('#museums-pills .chip').forEach(c => c.classList.remove('active'));
                chip.classList.add('active');
                currentConfig.PreferredSlug = slug;
                // Optionally auto-save? We'll wait for explicit save.
            });
            pillsDiv.appendChild(chip);
        }

        // Highlight preferred days
        const preferredDays = currentConfig.PreferredDays || [];
        document.querySelectorAll('#days-pills .chip').forEach(chip => {
            const day = chip.dataset.day;
            if (preferredDays.includes(day)) {
                chip.classList.add('active');
            }
            chip.addEventListener('click', (e) => {
                chip.classList.toggle('active');
                updatePreferredDays();
            });
        });
    } catch (err) {
        console.error(err);
        document.getElementById('config-loading').innerHTML = 'Failed to load config';
    }
}

function updatePreferredDays() {
    const selected = Array.from(document.querySelectorAll('#days-pills .chip.active')).map(c => c.dataset.day);
    // We'll store in currentConfig later when saving
}

async function saveConfig(e) {
    e.preventDefault();
    const mode = document.querySelector('input[name="mode"]:checked').value;
    const preferredSlug = document.querySelector('#museums-pills .chip.active')?.dataset.slug;
    const preferredDays = Array.from(document.querySelectorAll('#days-pills .chip.active')).map(c => c.dataset.day);
    const strikeTime = document.getElementById('strike-time').value;
    const checkWindowMinutes = parseInt(document.getElementById('check-window').value);
    const checkWindow = checkWindowMinutes * 60; // seconds

    const updatedConfig = {
        ...currentConfig,
        Mode: mode,
        PreferredSlug: preferredSlug,
        PreferredDays: preferredDays,
        StrikeTime: strikeTime,
        CheckWindow: checkWindow,
    };

    const res = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatedConfig)
    });
    if (res.ok) {
        M.toast({html: 'Configuration saved'});
        currentConfig = updatedConfig; // update local copy
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
        console.log('WebSocket closed, reconnecting in 3s...');
        setTimeout(connectWebSocket, 3000);
    };
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}
