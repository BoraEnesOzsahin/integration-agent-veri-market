// Kurum ajanı yerel paneli. Yalnızca 127.0.0.1'deki panel portundan yüklenir.
(function () {
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmtTime = iso => { if (!iso || iso === 'YOK') return '—'; const d = new Date(iso); return isNaN(d) ? iso : d.toLocaleString('tr-TR'); };

  let saved = {};      // anahtar -> { table, columns: { mantıksal: yerel } } (kayıtlı)
  let draft = {};      // ekrandaki eşleme
  let localTables = []; // kurumun tabloları ve sayısal sütunları
  let catalog = [];     // platformdaki veri setleri

  async function api(path, options = {}) {
    const res = await fetch('/api/v1/panel' + path, {
      ...options,
      headers: { 'X-Agent-Panel': '1', ...(options.body ? { 'Content-Type': 'application/json' } : {}) }
    });
    const text = await res.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch (e) { /* */ }
    if (!res.ok) throw new Error((json && json.message) || `HTTP ${res.status}`);
    return json;
  }

  const pill = (ok, yes, no, warn) => `<span class="pill ${ok ? 'ok' : warn ? 'warn' : 'bad'}">${esc(ok ? yes : no)}</span>`;

  async function loadOverview() {
    const o = await api('/overview');
    $('title').textContent = `${o.institutionName || o.institutionId} — Kurum Ajanı`;
    $('key').textContent = o.publicKeyHex || '';
    $('policy-file').textContent = o.policyFile || '';
    const m = o.manager || {};
    const heartbeat = m.lastSuccessfulHeartbeat && m.lastSuccessfulHeartbeat !== 'YOK' ? new Date(m.lastSuccessfulHeartbeat) : null;
    const fresh = heartbeat && (Date.now() - heartbeat.getTime()) < 60000;
    $('status').innerHTML = [
      ['Kurum', `<strong>${esc(o.institutionId)}</strong>`],
      ['Koordinatör bağlantısı', m.reachable === false ? pill(false, '', 'Yönetici servisi yanıt vermiyor')
        : pill(fresh, 'Bağlı', heartbeat ? 'Bağlantı kesik' : 'Hiç bağlanmadı') + `<div class="small faint mono">${esc(m.coordinatorUrl || '')}</div><div class="small faint">${esc(m.status || '')}</div>`],
      ['Son nabız', esc(fmtTime(m.lastSuccessfulHeartbeat))],
      ['Konsorsiyum onayı', m.reachable === false ? '—' : pill(!!m.isApproved, 'Onaylı', 'Onay bekliyor', true)],
      ['Veritabanı', pill(o.databaseConfigured, 'Ayarlı', 'Ayarlı değil')],
      ['Kanıt motoru', pill(o.zkpEngine, 'Çalışıyor', 'Ulaşılamıyor')],
      ['Koordinatöre bildirilen veri setleri', `<span class="mono">${esc(m.supportedDatasets || '—')}</span>`],
      ['Platform kataloğu', o.platformUrl ? `<span class="mono small">${esc(o.platformUrl)}</span>` : pill(false, '', 'PLATFORM_URL ayarlı değil')]
    ].map(([label, value]) => `<div class="stat"><div class="label">${label}</div><div>${value}</div></div>`).join('');
  }

  const copy = o => JSON.parse(JSON.stringify(o || {}));

  async function loadSchema() {
    const [schema, cat] = await Promise.all([api('/schema'), api('/catalog')]);
    localTables = (schema.tables || []).filter(t => t.numericColumns.length);
    saved = (schema.policy && schema.policy.datasets) || {};
    draft = copy(saved);
    catalog = cat.available ? cat.datasets : [];
    $('catalog-note').innerHTML = cat.available ? '' : `<div class="notice warn">${esc(cat.error)} Yalnızca kayıtlı eşlemeler gösteriliyor.</div>`;
    render();
  }

  // Katalogdaki veri setleri ve katalogda olmayan kayıtlı eşlemeler; önce üyesi olunanlar
  function entries() {
    const list = catalog.map(c => ({ key: c.key, names: c.names, columns: c.columns, member: c.member }));
    Object.keys(saved).filter(k => !list.some(e => e.key === k))
      .forEach(k => list.push({ key: k, names: [], columns: Object.fromEntries(Object.keys(saved[k].columns).map(c => [c, ''])), member: false, orphan: true }));
    return list.sort((a, b) => (b.member - a.member) || a.key.localeCompare(b.key));
  }

  function render() {
    const list = entries();
    if (!list.length) {
      $('tables').innerHTML = '<div class="notice">Platformda henüz veri seti yok. Platformda bir veri seti açıldığında burada görünür.</div>';
      updateDirty();
      return;
    }
    $('tables').innerHTML = list.map(e => {
      const m = draft[e.key] || { table: '', columns: {} };
      const table = localTables.find(t => t.name === m.table);
      const mapped = Object.values(m.columns || {}).filter(Boolean).length;
      const tableOptions = ['<option value="">— eşleme yok —</option>']
        .concat(localTables.map(t => `<option value="${esc(t.name)}" ${t.name === m.table ? 'selected' : ''}>${esc(t.name)} (${t.type === 'VIEW' ? 'görünüm' : 'tablo'}, ${t.rowCount == null ? '?' : t.rowCount.toLocaleString('tr-TR')} satır)</option>`));
      const rows = Object.entries(e.columns).map(([logical, label]) => {
        const options = ['<option value="">— paylaşma —</option>'].concat((table ? table.numericColumns : [])
          .map(c => `<option value="${esc(c)}" ${m.columns && m.columns[logical] === c ? 'selected' : ''}>${esc(c)}</option>`));
        return `<div><span class="mono">${esc(logical)}</span>${label ? `<div class="small faint">${esc(label)}</div>` : ''}</div>
          <select data-key="${esc(e.key)}" data-col="${esc(logical)}" ${table ? '' : 'disabled'}>${options.join('')}</select>`;
      }).join('');
      return `<div class="table-card ${mapped ? 'shared' : ''}">
        <div class="table-head">
          <div><strong>${esc(e.names.length ? e.names.join(', ') : e.key)}</strong>
            <div class="small faint">Anahtar: <span class="mono">${esc(e.key)}</span>${e.member ? ' · üyesiniz' : ''}${e.orphan ? ' · platformda artık yok' : ''}</div></div>
          <div>${mapped ? `<span class="pill ok">${mapped} sütun eşlendi</span>` : '<span class="pill warn">Eşlenmedi</span>'}</div>
        </div>
        <div class="map"><div class="small muted">Tablonuz</div><select data-key="${esc(e.key)}" data-table="1">${tableOptions.join('')}</select>${rows}</div>
      </div>`;
    }).join('');
    updateDirty();
  }

  function current() {
    const out = {};
    Object.entries(draft).forEach(([k, m]) => {
      const cols = Object.fromEntries(Object.entries(m.columns || {}).filter(([, v]) => v));
      if (m.table && Object.keys(cols).length) out[k] = { table: m.table, columns: cols };
    });
    return out;
  }

  function updateDirty() {
    const canonical = o => JSON.stringify(Object.keys(o).sort().map(k => [k, o[k].table, Object.entries(o[k].columns).sort()]));
    const dirty = canonical(current()) !== canonical(saved);
    $('save').disabled = !dirty;
    $('reset').disabled = !dirty;
    $('dirty').textContent = dirty ? 'Kaydedilmemiş değişiklik var.' : (Object.keys(saved).length ? '' : 'Henüz hiçbir veri seti eşlenmedi.');
  }

  $('tables').addEventListener('change', e => {
    const sel = e.target.closest('select[data-key]');
    if (!sel) return;
    const key = sel.dataset.key;
    draft[key] = draft[key] || { table: '', columns: {} };
    if (sel.dataset.table) {
      // Tablo değişince aynı adlı sütunlar önerilir; diğerleri boş kalır
      const table = localTables.find(t => t.name === sel.value);
      const logical = Object.keys((entries().find(x => x.key === key) || { columns: {} }).columns);
      draft[key] = { table: sel.value, columns: Object.fromEntries(logical.map(c => [c, table && table.numericColumns.includes(c) ? c : ''])) };
    } else {
      draft[key].columns[sel.dataset.col] = sel.value;
    }
    render();
  });

  $('reset').addEventListener('click', () => {
    draft = copy(saved);
    render();
    $('save-result').innerHTML = '';
  });

  $('save').addEventListener('click', async () => {
    $('save').disabled = true;
    try {
      const r = await api('/policy', { method: 'PUT', body: JSON.stringify({ datasets: current() }) });
      saved = r.policy.datasets || {};
      draft = copy(saved);
      $('save-result').innerHTML = r.coordinatorUpdated
        ? '<div class="notice ok">Kaydedildi. Koordinatör eşlenen veri setlerini aldı. Platformda veri setini yayınlayabilirsiniz.</div>'
        : '<div class="notice warn">Kaydedildi ve ajan yeni eşlemeyi hemen uyguluyor. Koordinatöre bildirilemedi: bağlantı gelince yönetici servisi kaydı yeniler.</div>';
      await loadOverview();
    } catch (e) {
      $('save-result').innerHTML = `<div class="notice bad">Kaydedilemedi: ${esc(e.message)}</div>`;
    }
    render();
  });

  async function loadActivity() {
    const rows = await api('/activity');
    $('activity').innerHTML = rows.length ? rows.map(r => `<tr>
      <td class="small">${esc(fmtTime(r.at))}</td>
      <td>${esc(r.kind)}</td>
      <td class="mono">${esc(r.table || '')}${r.column ? '.' + esc(r.column) : ''}</td>
      <td class="small">${esc(r.operation || '')}</td>
      <td>${r.ok ? '<span class="pill ok">Tamam</span>' : `<span class="pill bad">Reddedildi</span><div class="small faint">${esc(r.message || '')}</div>`}</td>
    </tr>`).join('') : '<tr><td colspan="5" class="faint">Ajan başladığından beri istek gelmedi.</td></tr>';
  }

  async function refresh() {
    try {
      await Promise.all([loadOverview(), loadActivity()]);
      $('updated').textContent = 'Güncellendi: ' + new Date().toLocaleTimeString('tr-TR');
    } catch (e) {
      $('updated').textContent = 'Ajana ulaşılamadı: ' + e.message;
    }
  }

  loadSchema().catch(e => { $('tables').innerHTML = `<div class="notice bad">Veri setleri okunamadı: ${esc(e.message)}</div>`; });
  refresh();
  setInterval(refresh, 10000);
})();
