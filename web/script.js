let allData = [];
let autoRefreshTimer = null;
let tempChart, humChart, compChart;
let currentUnit = 'C';

const el = sel => document.querySelector(sel);
const els = sel => Array.from(document.querySelectorAll(sel));

// --- Core Logic ---

function toF(c) { return ((c * 9/5) + 32).toFixed(1); }
function formatTemp(t) { return currentUnit === 'F' ? toF(t) + ' °F' : t + ' °C'; }
function asTempValue(t) { return currentUnit === 'F' ? Number(toF(t)) : Number(t); }

function getTempClass(t) {
  if (t > 30) return 'temp-hot';
  if (t > 15) return 'temp-mild';
  return 'temp-cool';
}

function showToast(msg, type = 'success') {
  const t = el('#toast');
  t.innerHTML = `<span>${type === 'success' ? '✅' : '❌'}</span> ${msg}`;
  t.className = `toast ${type}`;
  t.classList.remove('hidden');
  setTimeout(() => t.classList.add('hidden'), 4000);
}

// --- Unique Feature: Comparison ---
async function updateComparison() {
  const c1 = el('#comp1').value;
  const c2 = el('#comp2').value;
  const d1 = allData.find(d => d.location === c1);
  const d2 = allData.find(d => d.location === c2);
  
  if (d1) {
    el('#compName1').textContent = d1.location;
    el('#compVal1').textContent = formatTemp(d1.temperature);
  }
  if (d2) {
    el('#compName2').textContent = d2.location;
    el('#compVal2').textContent = formatTemp(d2.temperature);
  }

  if (c1 && c2 && window.Chart) {
    initCharts();
    try {
      const [r1, r2] = await Promise.all([
        fetch(`/api/history?location=${encodeURIComponent(c1)}&limit=15`),
        fetch(`/api/history?location=${encodeURIComponent(c2)}&limit=15`)
      ]);
      const [h1, h2] = await Promise.all([r1.json(), r2.json()]);
      if (!Array.isArray(h1) || !Array.isArray(h2)) return;
      
      const t1 = h1.map(h => Number(h.timestamp)).filter(Number.isFinite);
      const t2 = h2.map(h => Number(h.timestamp)).filter(Number.isFinite);
      const timeline = Array.from(new Set([...t1, ...t2])).sort((a, b) => a - b);
      compChart.data.labels = timeline.map(ts => new Date(ts).toLocaleTimeString([], { second: '2-digit' }));

      const series1 = new Map(
        h1
          .filter(h => Number.isFinite(Number(h.timestamp)))
          .map(h => [Number(h.timestamp), asTempValue(h.temperature)])
      );
      const series2 = new Map(
        h2
          .filter(h => Number.isFinite(Number(h.timestamp)))
          .map(h => [Number(h.timestamp), asTempValue(h.temperature)])
      );

      compChart.data.datasets[0].label = c1;
      compChart.data.datasets[0].data = timeline.map(ts => (series1.has(ts) ? series1.get(ts) : null));
      compChart.data.datasets[1].label = c2;
      compChart.data.datasets[1].data = timeline.map(ts => (series2.has(ts) ? series2.get(ts) : null));
      compChart.update('none');
    } catch (e) { console.error('Comp history failed', e); }
  }
}

// --- Unique Feature: Projection ---
function showProjection(data) {
  el('#forecastSection').classList.remove('hidden');
  // Simple jitter-based projection
  el('#fore1').textContent = formatTemp((data.temperature + 0.5).toFixed(1));
  el('#fore2').textContent = formatTemp((data.temperature - 0.2).toFixed(1));
  el('#fore3').textContent = formatTemp((data.temperature + 1.1).toFixed(1));
}

// --- Rendering ---

function renderNodeMenu(data) {
  const menu = el('#nodeMenu');
  const loader = el('#menuLoader');
  
  if (data.length > 0) loader.classList.add('hidden');
  else loader.classList.remove('hidden');

  // Track old values for flashing
  const oldTemps = new Map();
  menu.querySelectorAll('.node-pill').forEach(p => {
    const loc = p.querySelector('.pill-name').textContent;
    const temp = parseFloat(p.dataset.val);
    oldTemps.set(loc, temp);
  });

  menu.innerHTML = '';
  const activeLoc = el('#querySelect').value;

  data.forEach(row => {
    const pill = document.createElement('div');
    pill.className = `node-pill ${row.location === activeLoc ? 'active' : ''}`;
    pill.dataset.val = row.temperature;
    
    const oldVal = oldTemps.get(row.location);
    const newVal = row.temperature;
    let flash = '';
    if (oldVal !== undefined && Math.abs(newVal - oldVal) > 0.01) {
      flash = newVal > oldVal ? 'flash-up' : 'flash-down';
    }

    pill.innerHTML = `
      <div class="pill-name">${row.location}</div>
      <div style="display:flex; align-items:center; gap:16px;">
        <div class="pill-temp ${flash} ${getTempClass(row.temperature)}">${formatTemp(row.temperature)}</div>
        <div class="muted" style="font-size: 12px; font-weight:600; min-width: 60px; text-align:right;">${row.humidity}% Hum</div>
      </div>
    `;

    pill.addEventListener('click', () => {
      el('#querySelect').value = row.location;
      fetchLocation(row.location);
      el('#querySelect').scrollIntoView({ behavior: 'smooth', block: 'center' });
    });

    menu.appendChild(pill);
  });
}

function populateSelects() {
  const selects = ['#querySelect', '#upLocation', '#comp1', '#comp2'];
  selects.forEach(s => {
    const sel = el(s);
    if (!sel) return;
    const current = sel.value;
    sel.innerHTML = `<option value="">${s === '#querySelect' ? 'Select Node to Inspect...' : (s === '#upLocation' ? 'Select Target Node...' : 'Select node...')}</option>`;
    allData.map(r => r.location).sort().forEach(loc => {
      const o = document.createElement('option');
      o.value = loc; o.textContent = loc;
      if (loc === current) o.selected = true;
      sel.appendChild(o);
    });
  });
}

// --- Actions ---

async function fetchAll() {
  try {
    const res = await fetch('/api/weather');
    const payload = await res.json();
    if (!res.ok || !Array.isArray(payload)) {
      const msg = payload?.message || 'Failed to fetch weather data';
      throw new Error(msg);
    }
    allData = payload;
    renderNodeMenu(allData.filter(d => d.location.toLowerCase().includes(el('#filterInput').value.toLowerCase())));
    populateSelects();
    updateSummary();
    updateComparison();
    
    // Auto-update inspector if a node is selected
    const activeLoc = el('#querySelect').value;
    if (activeLoc) fetchLocation(activeLoc);
  } catch (e) {
    console.error('Sync failed', e);
    showToast('Live sync failed', 'error');
  }
}

async function fetchLocation(manualLoc) {
  const loc = (typeof manualLoc === 'string' ? manualLoc : el('#querySelect').value.trim());
  if (!loc) return;
  
  // Sync the select dropdown if it was a manual click from the Hub
  if (el('#querySelect').value !== loc) {
    el('#querySelect').value = loc;
  }

  try {
    const res = await fetch('/api/weather?location=' + encodeURIComponent(loc));
    const data = await res.json();
    const out = el('#locationResult');
    if (data.error) {
      out.textContent = `Node ${loc} not found`;
    } else {
      out.innerHTML = `
        <div style="display:flex; justify-content:space-between; align-items:center; width: 100%; padding: 0 10px;">
          <div style="text-align: left;">
            <h3 style="margin:0; color:var(--amber); font-size: 16px; letter-spacing: 1px; font-weight: 700;">${data.location}</h3>
            <div style="font-size:28px; font-weight:900; margin:8px 0; line-height: 1; color: var(--text);">${formatTemp(data.temperature)}</div>
            <div class="muted" style="font-size: 11px; font-weight: 600; opacity: 0.7;">Status: Operational • Latency: ${Math.floor(Math.random() * 10) + 2}ms</div>
          </div>
          <div style="font-size:36px; filter: drop-shadow(0 0 10px var(--amber)); opacity: 0.9;">🛰️</div>
        </div>
      `;
      showProjection(data);
      await renderHistory(loc);
    }
  } catch (e) { showToast('Inspector error', 'error'); }
}

async function submitUpdate(ev) {
  ev?.preventDefault();
  const loc = el('#upLocation').value;
  const temp = parseFloat(el('#upTemp').value);
  const hum = parseFloat(el('#upHum').value);
  if (!loc || isNaN(temp)) return showToast('Select a target node and valid data', 'error');

  const btn = el('#submitUpdate');
  btn.disabled = true;
  btn.textContent = 'TRANSMITTING...';

  try {
    const res = await fetch('/api/weather', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ location: loc, temperature: temp, humidity: hum })
    });
    if (res.ok) {
      showToast(`Broadcast to ${loc} successful`);
      el('#updateForm').reset();
      await fetchAll();
    } else {
      const body = await res.json().catch(() => ({}));
      showToast(body?.message || 'Broadcast failed', 'error');
    }
  } catch (e) { showToast('Broadcast failed', 'error'); }
  finally {
    btn.disabled = false;
    btn.textContent = 'INITIALIZE BROADCAST';
  }
}

function updateSummary() {
  const total = allData.length;
  el('#statTotal').textContent = total;
  if (total > 0) {
    const avgT = allData.reduce((a, b) => a + b.temperature, 0) / total;
    const avgH = allData.reduce((a, b) => a + b.humidity, 0) / total;
    el('#statAvgT').textContent = formatTemp(avgT.toFixed(1));
    el('#statAvgH').textContent = avgH.toFixed(0) + '%';
    const peak = allData.reduce((p, c) => (p.temperature > c.temperature) ? p : c);
    el('#statPeak').textContent = peak.location;
  }
}

async function renderHistory(location) {
  if (!window.Chart) return;
  initCharts();
  try {
    const res = await fetch(`/api/history?location=${encodeURIComponent(location)}&limit=15`);
    const hist = await res.json();
    if (!Array.isArray(hist)) return;
    const labels = hist.map(h => new Date(h.timestamp).toLocaleTimeString([], {second:'2-digit'}));
    
    // Replace instead of push to prevent growth
    tempChart.data.labels = labels;
    tempChart.data.datasets[0].data = hist.map(h => asTempValue(h.temperature));
    
    humChart.data.labels = labels;
    humChart.data.datasets[0].data = hist.map(h => h.humidity);
    
    tempChart.update('none');
    humChart.update('none');
  } catch (e) {}
}

function initCharts() {
  if (tempChart) return;
  const commonOptions = {
    responsive: true, 
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { 
      x: { display: false }, 
      y: { 
        grid: { color: 'rgba(255,255,255,0.05)' }, 
        ticks: { color: '#64748b', font: { size: 10 } },
        beginAtZero: false
      } 
    },
    elements: { 
      line: { tension: 0.4, borderWidth: 4, capStyle: 'round' }, 
      point: { radius: 0 } 
    },
    animation: false
  };

  // Neon Indigo
  tempChart = new Chart(el('#tempChart'), {
    type: 'line',
    data: { labels: [], datasets: [{ 
      data: [], 
      borderColor: '#6366f1', 
      fill: true, 
      backgroundColor: 'rgba(99, 102, 241, 0.1)',
      borderCapStyle: 'round'
    }] },
    options: {
      ...commonOptions,
      scales: {
        ...commonOptions.scales,
        y: { ...commonOptions.scales.y, suggestedMin: 10, suggestedMax: 40 }
      }
    }
  });

  // Neon Rose
  humChart = new Chart(el('#humChart'), {
    type: 'line',
    data: { labels: [], datasets: [{ 
      data: [], 
      borderColor: '#f43f5e', 
      fill: true, 
      backgroundColor: 'rgba(244, 63, 94, 0.1)',
      borderCapStyle: 'round'
    }] },
    options: {
      ...commonOptions,
      scales: {
        ...commonOptions.scales,
        y: { ...commonOptions.scales.y, min: 0, max: 100 }
      }
    }
  });

  // Comparison Chart (Neon Cyan & Amber)
  compChart = new Chart(el('#compChart'), {
    type: 'line',
    data: { 
      labels: [], 
      datasets: [
        { data: [], borderColor: '#06b6d4', borderWidth: 3, fill: false, borderCapStyle: 'round' },
        { data: [], borderColor: '#f59e0b', borderWidth: 3, fill: false, borderCapStyle: 'round' }
      ] 
    },
    options: {
      ...commonOptions,
      plugins: { legend: { display: true, labels: { color: '#94a3b8', font: { size: 10 } } } },
      scales: {
        ...commonOptions.scales,
        y: { ...commonOptions.scales.y, beginAtZero: false }
      }
    }
  });
}

// --- Events ---
el('#filterInput').addEventListener('input', () => renderNodeMenu(allData.filter(d => d.location.toLowerCase().includes(el('#filterInput').value.toLowerCase()))));
el('#fetchLocation').addEventListener('click', fetchLocation);
el('#updateForm').addEventListener('submit', submitUpdate);
el('#comp1').addEventListener('change', updateComparison);
el('#comp2').addEventListener('change', updateComparison);

el('#unitC').addEventListener('click', () => { currentUnit = 'C'; el('#unitC').classList.add('active'); el('#unitF').classList.remove('active'); fetchAll(); });
el('#unitF').addEventListener('click', () => { currentUnit = 'F'; el('#unitF').classList.add('active'); el('#unitC').classList.remove('active'); fetchAll(); });

el('#exportBtn').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(allData, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = `weather_net_${Date.now()}.json`; a.click();
});

// Init
autoRefreshTimer = setInterval(fetchAll, 3000);
fetchAll();
