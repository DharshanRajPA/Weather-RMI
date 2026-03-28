let allData = [];
let autoRefreshTimer = null;
let tempChart, humChart;

const el = sel => document.querySelector(sel);
const els = sel => Array.from(document.querySelectorAll(sel));

function showToast(msg, type = 'success') {
  const t = el('#toast');
  t.textContent = msg;
  t.className = `toast ${type}`;
  t.classList.remove('hidden');
  setTimeout(() => t.classList.add('hidden'), 2500);
}

function setLoading(state) {
  const wrap = el('#tableWrap');
  if (!wrap) return;
  wrap.classList.toggle('loading', !!state);
}

function renderAllTable(data) {
  const tbody = el('#allTable tbody');
  const empty = el('.empty');
  tbody.innerHTML = '';
  if (!data || data.length === 0) {
    empty.classList.remove('hidden');
    return;
  }
  empty.classList.add('hidden');
  data.forEach(row => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${row.location}</td><td>${row.temperature}</td><td>${row.humidity}</td><td>${new Date(row.timestamp).toLocaleString()}</td>`;
    tbody.appendChild(tr);
  });
}

function populateSelect() {
  const sel = el('#querySelect');
  sel.innerHTML = '';
  const placeholder = document.createElement('option');
  placeholder.value = '';
  placeholder.textContent = 'Select a location';
  sel.appendChild(placeholder);
  allData
    .map(r => r.location)
    .sort((a,b) => a.localeCompare(b))
    .forEach(loc => {
      const o = document.createElement('option');
      o.value = loc; o.textContent = loc; sel.appendChild(o);
    });
}

async function fetchAll() {
  try {
    setLoading(true);
    const res = await fetch('/api/weather');
    const json = await res.json();
    allData = json;
    renderAllTable(filterData(allData));
    populateSelect();
    await renderSummary();
  } catch (e) {
    showToast('Failed to load data', 'error');
  } finally {
    setLoading(false);
  }
}

function filterData(data) {
  const q = el('#filterInput').value.trim().toLowerCase();
  if (!q) return data;
  return data.filter(d => d.location.toLowerCase().includes(q));
}

el('#filterInput').addEventListener('input', () => {
  renderAllTable(filterData(allData));
});

function getQueryInput() {
  const fromSelect = el('#querySelect').value.trim();
  const manual = el('#queryLocation').value.trim();
  return manual || fromSelect;
}

async function fetchLocation() {
  const loc = getQueryInput();
  if (!loc) return;
  const btn = el('#fetchLocation');
  btn.disabled = true;
  try {
    const res = await fetch('/api/weather?location=' + encodeURIComponent(loc));
    const data = await res.json();
    const out = el('#locationResult');
    if (data.error) {
      out.textContent = `Error: ${data.message}`;
      out.classList.add('muted');
    } else {
      out.classList.remove('muted');
      out.innerHTML = `<strong>${data.location}</strong><br/>Temp: ${data.temperature} °C — Humidity: ${data.humidity} %<br/><small>${new Date(data.timestamp).toLocaleString()}</small>`;
      await renderHistory(loc);
    }
  } catch (e) {
    showToast('Failed to fetch location', 'error');
  } finally {
    btn.disabled = false;
  }
}

async function submitUpdate(ev) {
  ev?.preventDefault();
  const loc = el('#upLocation').value.trim();
  const temp = parseFloat(el('#upTemp').value);
  const hum = parseFloat(el('#upHum').value);
  const status = el('#updateStatus');
  const btn = el('#submitUpdate');
  if (!loc || Number.isNaN(temp) || Number.isNaN(hum)) {
    status.textContent = 'Please provide location, temperature, and humidity';
    showToast('Invalid form input', 'error');
    return;
  }
  btn.disabled = true;
  try {
    const res = await fetch('/api/weather', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ location: loc, temperature: temp, humidity: hum })
    });
    const data = await res.json().catch(() => ({ status: res.status }));
    if (res.ok) {
      status.textContent = 'Update successful';
      showToast('Weather updated', 'success');
      el('#updateForm').reset();
      await fetchAll();
    } else {
      status.textContent = 'Update failed: ' + (data.message || res.status);
      showToast('Update failed', 'error');
    }
  } catch (e) {
    status.textContent = 'Network error';
    showToast('Network error', 'error');
  } finally {
    btn.disabled = false;
  }
}

function setupAutoRefresh() {
  const chk = el('#autoRefresh');
  chk.addEventListener('change', () => {
    if (chk.checked) {
      autoRefreshTimer = setInterval(fetchAll, 5000);
      showToast('Auto refresh enabled');
    } else {
      clearInterval(autoRefreshTimer);
      autoRefreshTimer = null;
      showToast('Auto refresh disabled');
    }
  });
}

el('#refreshAll').addEventListener('click', fetchAll);
el('#fetchLocation').addEventListener('click', fetchLocation);
el('#updateForm').addEventListener('submit', submitUpdate);

setupAutoRefresh();
fetchAll();

function initChartsOnce() {
  if (tempChart && humChart) return;
  const ctxT = el('#tempChart');
  const ctxH = el('#humChart');
  if (!ctxT || !window.Chart) return;
  tempChart = new Chart(ctxT, {
    type: 'line',
    data: { labels: [], datasets: [{ label: 'Temperature (°C)', data: [], borderColor: '#22d3ee' }] },
    options: { responsive: true, plugins: { legend: { display: false } }, scales: { x: { ticks: { color: '#9aa3af' } }, y: { ticks: { color: '#9aa3af' } } } }
  });
  humChart = new Chart(ctxH, {
    type: 'line',
    data: { labels: [], datasets: [{ label: 'Humidity (%)', data: [], borderColor: '#7c3aed' }] },
    options: { responsive: true, plugins: { legend: { display: false } }, scales: { x: { ticks: { color: '#9aa3af' } }, y: { ticks: { color: '#9aa3af' } } } }
  });
}

async function renderHistory(location) {
  initChartsOnce();
  if (!tempChart || !humChart) return;
  try {
    const res = await fetch(`/api/history?location=${encodeURIComponent(location)}&limit=50`);
    const hist = await res.json();
    const labels = hist.map(h => new Date(h.timestamp).toLocaleTimeString());
    tempChart.data.labels = labels;
    tempChart.data.datasets[0].data = hist.map(h => h.temperature);
    humChart.data.labels = labels;
    humChart.data.datasets[0].data = hist.map(h => h.humidity);
    tempChart.update(); humChart.update();
  } catch (e) {
    // Fallback: show a snapshot using current data if history endpoint not available
    const d = allData.find(x => x.location.toLowerCase() === location.toLowerCase());
    if (!d) return;
    tempChart.data.labels = [new Date(d.timestamp).toLocaleTimeString()];
    tempChart.data.datasets[0].data = [d.temperature];
    humChart.data.labels = [new Date(d.timestamp).toLocaleTimeString()];
    humChart.data.datasets[0].data = [d.humidity];
    tempChart.update(); humChart.update();
  }
}

async function renderSummary() {
  const totalEl = el('#statTotal');
  const avgTEl = el('#statAvgT');
  const avgHEl = el('#statAvgH');
  const latestEl = el('#statLatest');
  try {
    const res = await fetch('/api/summary');
    if (res.ok) {
      const s = await res.json();
      totalEl.textContent = s.totalLocations ?? '—';
      avgTEl.textContent = s.avgTemperature ?? '—';
      avgHEl.textContent = s.avgHumidity ?? '—';
      latestEl.textContent = s.latestLocation ? `${s.latestLocation} @ ${new Date(s.latestTimestamp).toLocaleTimeString()}` : '—';
      return;
    }
  } catch (_) {}
  // Fallback client-side summary
  const total = allData.length;
  totalEl.textContent = total || '—';
  if (total) {
    const avgT = (allData.reduce((a,b) => a + Number(b.temperature), 0) / total).toFixed(2);
    const avgH = (allData.reduce((a,b) => a + Number(b.humidity), 0) / total).toFixed(2);
    avgTEl.textContent = avgT;
    avgHEl.textContent = avgH;
    const latest = allData.reduce((acc, cur) => (cur.timestamp > (acc?.timestamp ?? 0) ? cur : acc), null);
    latestEl.textContent = latest ? `${latest.location} @ ${new Date(latest.timestamp).toLocaleTimeString()}` : '—';
  } else {
    avgTEl.textContent = '—';
    avgHEl.textContent = '—';
    latestEl.textContent = '—';
  }
}
