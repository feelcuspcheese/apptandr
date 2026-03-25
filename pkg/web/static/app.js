let ws;

document.addEventListener('DOMContentLoaded', () => {
    M.AutoInit();
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
    const res = await fetch('/api/config');
    const cfg = await res.json();
    document.getElementById('config-loading').style.display = 'none';
    document.getElementById('strike-time').value = cfg.StrikeTime;
    document.getElementById('check-window').value = cfg.CheckWindow / 60; // minutes
    document.querySelector(`input[name="mode"][value="${cfg.Mode}"]`).checked = true;

    // Populate museum pills
    const pillsDiv = document.getElementById('museums-pills');
    pillsDiv.innerHTML = '';
    for (const [slug, info] of Object.entries(cfg.Sites)) {
        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = info.Name || slug;
        chip.dataset.slug = slug;
        if (cfg.PreferredSlug === slug) {
            chip.classList.add('active');
        }
        chip.addEventListener('click', () => {
            document.querySelectorAll('#museums-pills .chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            cfg.PreferredSlug = slug;
            // Optionally save immediately
        });
        pillsDiv.appendChild(chip);
    }

    // Highlight preferred days
    const days = cfg.PreferredDays;
    document.querySelectorAll('#days-pills .chip').forEach(chip => {
        if (days.includes(chip.dataset.day)) {
            chip.classList.add('active');
        }
        chip.addEventListener('click', () => {
            chip.classList.toggle('active');
            updatePreferredDays();
        });
    });
}

function updatePreferredDays() {
    const selected = Array.from(document.querySelectorAll('#days-pills .chip.active')).map(c => c.dataset.day);
    document.getElementById('preferred-days-input').value = JSON.stringify(selected);
}

async function saveConfig(e) {
    e.preventDefault();
    const formData = new FormData(document.getElementById('config-form'));
    const mode = document.querySelector('input[name="mode"]:checked').value;
    const preferredSlug = document.querySelector('#museums-pills .chip.active')?.dataset.slug;
    const preferredDays = Array.from(document.querySelectorAll('#days-pills .chip.active')).map(c => c.dataset.day);
    const strikeTime = document.getElementById('strike-time').value;
    const checkWindowMinutes = parseInt(document.getElementById('check-window').value);
    const checkWindow = checkWindowMinutes * 60; // seconds

    const newCfg = {
        Mode: mode,
        PreferredSlug: preferredSlug,
        PreferredDays: preferredDays,
        StrikeTime: strikeTime,
        CheckWindow: checkWindow,
        // other fields we keep as is (from current config)
        // In a full implementation we'd fetch and merge
    };

    const res = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newCfg)
    });
    if (res.ok) {
        M.toast({html: 'Config saved'});
    } else {
        M.toast({html: 'Error saving config', classes: 'red'});
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
    if (!dateTime) return;
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
        line.textContent = new Date().toLocaleTimeString() + ' ' + event.data;
        logsDiv.appendChild(line);
        logsDiv.scrollTop = logsDiv.scrollHeight;
    };
    ws.onclose = () => {
        setTimeout(connectWebSocket, 3000);
    };
}
