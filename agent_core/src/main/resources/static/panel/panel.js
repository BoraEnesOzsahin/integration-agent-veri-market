// Kurum ajanı yerel paneli. Yalnızca 127.0.0.1'deki panel portundan yüklenir.
(function () {
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmtTime = iso => { if (!iso || iso === 'YOK') return '—'; const d = new Date(iso); return isNaN(d) ? iso : d.toLocaleString('tr-TR'); };

  let saved = {};      // tablo -> [sütun] (kayıtlı)
  let selected = {};   // tablo -> Set(sütun) (ekranda)

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
      ['Koordinatöre bildirilen tablolar', `<span class="mono">${esc(m.supportedDatasets || '—')}</span>`]
    ].map(([label, value]) => `<div class="stat"><div class="label">${label}</div><div>${value}</div></div>`).join('');
  }

  async function loadSchema() {
    const s = await api('/schema');
    saved = (s.policy && s.policy.tables) || {};
    selected = {};
    Object.entries(saved).forEach(([t, cols]) => { selected[t] = new Set(cols); });
    renderTables(s.tables || []);
    updateDirty();
  }

  let lastTables = [];
  function renderTables(tables) {
    lastTables = tables;
    if (!tables.length) {
      $('tables').innerHTML = '<div class="notice warn">Veritabanında tablo bulunamadı. Ajanın bağlandığı veritabanını ve kullanıcının okuma iznini kontrol edin.</div>';
      return;
    }
    // Veritabanından silinmiş ama listede kalmış tablolar da gösterilir ki kaldırılabilsin
    const missing = Object.keys(saved).filter(t => !tables.some(x => x.name === t));
    $('tables').innerHTML = tables.map(t => {
      const sel = selected[t.name] || new Set();
      const count = t.numericColumns.length;
      return `<div class="table-card ${sel.size ? 'shared' : ''}">
        <div class="table-head">
          <div><strong class="mono" style="font-size:14px">${esc(t.name)}</strong>
            <span class="small faint">${t.type === 'VIEW' ? 'görünüm' : 'tablo'} · ${t.rowCount == null ? 'satır sayısı okunamadı' : t.rowCount.toLocaleString('tr-TR') + ' satır'}</span></div>
          <div>${sel.size ? `<span class="pill ok">${sel.size} sütun paylaşılıyor</span>` : '<span class="pill warn">Paylaşılmıyor</span>'}
            ${count ? `<button data-all="${esc(t.name)}">${sel.size === count ? 'Hiçbirini seçme' : 'Tümünü seç'}</button>` : ''}</div>
        </div>
        <div class="columns">
          ${count ? t.numericColumns.map(c => `<label class="col"><input type="checkbox" data-table="${esc(t.name)}" data-col="${esc(c)}" ${sel.has(c) ? 'checked' : ''}><span class="mono">${esc(c)}</span></label>`).join('')
            : '<span class="small faint">Sayısal sütun yok: bu tablo paylaşılamaz.</span>'}
        </div>
        ${t.otherColumns.length ? `<div class="small faint" style="margin-top:6px">Paylaşılamaz (sayısal değil veya kimlik): <span class="mono">${esc(t.otherColumns.join(', '))}</span></div>` : ''}
      </div>`;
    }).join('') + missing.map(t => `<div class="table-card"><div class="notice bad"><span class="mono">${esc(t)}</span> listede ama veritabanında yok. Kaydettiğinizde listeden çıkar.</div></div>`).join('');
  }

  function current() {
    const out = {};
    Object.entries(selected).forEach(([t, set]) => {
      if (set.size && lastTables.some(x => x.name === t)) out[t] = [...set].sort();
    });
    return out;
  }

  function updateDirty() {
    const canonical = o => JSON.stringify(Object.keys(o).sort().map(t => [t, [...o[t]].sort()]));
    const dirty = canonical(current()) !== canonical(saved);
    $('save').disabled = !dirty;
    $('reset').disabled = !dirty;
    $('dirty').textContent = dirty ? 'Kaydedilmemiş değişiklik var.' : (Object.keys(saved).length ? '' : 'Henüz hiçbir tablo paylaşılmıyor.');
  }

  $('tables').addEventListener('change', e => {
    const box = e.target.closest('input[data-table]');
    if (!box) return;
    const t = box.dataset.table;
    selected[t] = selected[t] || new Set();
    box.checked ? selected[t].add(box.dataset.col) : selected[t].delete(box.dataset.col);
    renderTables(lastTables);
    updateDirty();
  });

  $('tables').addEventListener('click', e => {
    const btn = e.target.closest('button[data-all]');
    if (!btn) return;
    const t = lastTables.find(x => x.name === btn.dataset.all);
    const sel = selected[t.name] || new Set();
    selected[t.name] = sel.size === t.numericColumns.length ? new Set() : new Set(t.numericColumns);
    renderTables(lastTables);
    updateDirty();
  });

  $('reset').addEventListener('click', () => {
    selected = {};
    Object.entries(saved).forEach(([t, cols]) => { selected[t] = new Set(cols); });
    renderTables(lastTables);
    updateDirty();
    $('save-result').innerHTML = '';
  });

  $('save').addEventListener('click', async () => {
    $('save').disabled = true;
    try {
      const r = await api('/policy', { method: 'PUT', body: JSON.stringify({ tables: current() }) });
      saved = r.policy.tables || {};
      $('save-result').innerHTML = r.coordinatorUpdated
        ? '<div class="notice ok">Kaydedildi. Koordinatör yeni tablo listesini aldı.</div>'
        : '<div class="notice warn">Kaydedildi ve ajan yeni listeyi hemen uyguluyor. Koordinatöre bildirilemedi: bağlantı gelince yönetici servisi kaydı yeniler.</div>';
      await loadOverview();
    } catch (e) {
      $('save-result').innerHTML = `<div class="notice bad">Kaydedilemedi: ${esc(e.message)}</div>`;
    }
    renderTables(lastTables);
    updateDirty();
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

  loadSchema().catch(e => { $('tables').innerHTML = `<div class="notice bad">Şema okunamadı: ${esc(e.message)}</div>`; });
  refresh();
  setInterval(refresh, 10000);
})();
