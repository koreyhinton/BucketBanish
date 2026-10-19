// BucketBanish UI. Read-only: marking photos only builds a report; deletion happens in the S3 console.

const PAGE = 25;

const state = {
  info: null,
  result: null,
  tab: 'bursts',
  shown: { bursts: PAGE, sizes: PAGE, names: PAGE },
  marked: new Map(), // uri -> PhotoView
  report: null,
};

const $ = (sel) => document.querySelector(sel);

/** Tiny element builder. Strings become text nodes, so S3 keys can never inject HTML. */
function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

// ---------- formatting ----------

function fmtBytes(n) {
  if (n === null || n === undefined) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v < 10 ? 2 : 1)} ${units[i]}`;
}

/** EXIF capture time is camera-local with no zone, so show it verbatim (to the millisecond). */
function fmtCapture(t) {
  if (!t) return 'no capture date';
  const [date, time = ''] = t.split('T');
  const [hms, frac = ''] = time.split('.');
  return `${date} ${hms}${frac ? '.' + (frac + '000').slice(0, 3) : ''}`;
}

function fmtInstant(t) {
  return t ? new Date(t).toLocaleString() : '—';
}

function dims(p) {
  return p.width ? `${p.width}×${p.height}` : '—';
}

function thumbUrl(uri, size) {
  return `api/thumbnail?uri=${encodeURIComponent(uri)}&size=${size}`;
}

// ---------- API ----------

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try { msg = (await res.json()).error || msg; } catch { /* not JSON */ }
    throw new Error(msg);
  }
  return res;
}

// ---------- scanning ----------

async function startScan(event) {
  event.preventDefault();
  const err = $('#form-error');
  err.hidden = true;
  const body = {
    targets: $('#targets').value.split('\n').map((s) => s.trim()).filter(Boolean),
    gapSeconds: Number($('#gap').value),
    threshold: Number($('#threshold').value),
    minSize: Math.round(Number($('#min-size').value) * 1024),
    visual: $('#visual').checked,
    verify: $('#verify').checked,
    anyCamera: $('#any-camera').checked,
    ignoreCase: $('#ignore-case').checked,
  };
  $('#scan-btn').disabled = true;
  $('#scan-btn').setAttribute('aria-busy', 'true');
  try {
    const job = await (await api('api/scans', { method: 'POST', body: JSON.stringify(body) })).json();
    $('#progress').hidden = false;
    await poll(job.id);
  } catch (e) {
    err.textContent = e.message;
    err.hidden = false;
  } finally {
    $('#scan-btn').disabled = false;
    $('#scan-btn').removeAttribute('aria-busy');
    $('#progress').hidden = true;
  }
}

async function poll(id) {
  for (;;) {
    const s = await (await api(`api/scans/${id}`)).json();
    $('#phase').textContent = s.phase.charAt(0).toUpperCase() + s.phase.slice(1) + '…';
    $('#count').textContent = s.total ? `${s.done} / ${s.total}` : '';
    const bar = $('#bar');
    if (s.total) { bar.max = s.total; bar.value = s.done; } else { bar.removeAttribute('value'); }
    if (s.status === 'DONE') { showResult(s.result); return; }
    if (s.status === 'FAILED') throw new Error(`Scan failed: ${s.error}`);
    await new Promise((r) => setTimeout(r, 600));
  }
}

function showResult(result) {
  state.result = result;
  state.shown = { bursts: PAGE, sizes: PAGE, names: PAGE };
  state.report = null;
  if (state.tab === 'banish' || tabItems(state.tab).length === 0) {
    state.tab = ['bursts', 'sizes', 'names'].find((t) => tabItems(t).length) || 'bursts';
  }
  $('#results').hidden = false;
  renderSummary();
  render();
}

function tabItems(tab) {
  const r = state.result;
  if (!r) return [];
  return { bursts: r.bursts, sizes: r.sizeGroups, names: r.nameGroups }[tab] || [];
}

// ---------- rendering ----------

function renderSummary() {
  const r = state.result;
  const stat = (value, label) => h('article', {}, h('b', {}, value), h('span', {}, label));
  $('#summary').replaceChildren(
    stat(r.photoCount.toLocaleString(), `photos in ${r.targets.length} location${r.targets.length === 1 ? '' : 's'}`),
    stat(r.bursts.length, 'similar burst sets'),
    stat(r.sizeGroups.length, 'same-size groups'),
    stat(fmtBytes(r.identicalBytes), 'in identical extra copies'),
    stat(r.nameGroups.length, 'filenames in 2+ buckets'),
  );
  const notes = [];
  if (r.undatedCount) {
    notes.push(h('div', { class: 'notice notice-info' },
      `${r.undatedCount} photo(s) have no EXIF capture date and can't be grouped into bursts.`));
  }
  if (r.warnings.length) {
    notes.push(h('div', { class: 'notice notice-warn' }, 'Some objects could not be read:',
      h('ul', {}, r.warnings.map((w) => h('li', {}, w)))));
  }
  $('#warnings').replaceChildren(...notes);
}

function render() {
  for (const btn of document.querySelectorAll('.tabs button')) {
    const active = btn.dataset.tab === state.tab;
    btn.className = active ? '' : 'secondary outline';
    if (active) btn.setAttribute('aria-current', 'page'); else btn.removeAttribute('aria-current');
  }
  for (const t of ['bursts', 'sizes', 'names']) {
    document.querySelector(`[data-count="${t}"]`).textContent = tabItems(t).length;
  }
  updateMarkedCount();
  const body = $('#tab-body');
  const renderers = { bursts: renderBursts, sizes: renderSizes, names: renderNames, banish: renderBanish };
  body.replaceChildren(...renderers[state.tab]());
}

function updateMarkedCount() {
  document.querySelector('[data-count="banish"]').textContent = state.marked.size;
}

function paged(tab, items, toCard, emptyText) {
  if (!items.length) return [h('p', { class: 'empty' }, emptyText)];
  const cards = items.slice(0, state.shown[tab]).map(toCard);
  if (items.length > state.shown[tab]) {
    cards.push(h('button', {
      class: 'outline more',
      onclick: () => { state.shown[tab] += PAGE; render(); },
    }, `Show more (${items.length - state.shown[tab]} remaining)`));
  }
  return cards;
}

function renderBursts() {
  const intro = h('div', { class: 'notice notice-info' },
    'Photos from the same camera taken within the burst gap that look alike. Mark the shots you don\'t need. ',
    'The number on each tile is its visual difference from the first shot (0 means practically identical).');
  return [intro, ...paged('bursts', state.result.bursts, (set, i) => groupCard({
    title: `${set.photos.length} photos over ${set.spanSeconds.toFixed(2)}s`,
    meta: `${set.camera} · starts ${fmtCapture(set.photos[0].captureTime)}`,
    pills: set.visuallyCompared ? [] : [['Time only: preview format not comparable', 'pill-warn']],
    photos: set.photos,
    extra: (p, idx) => set.distances[idx] === null ? null : `visual diff ${set.distances[idx]}`,
    compareRows: [['Visual diff vs #1', (p, idx) => set.distances[idx] ?? '—']],
    keepLabel: 'Mark all but largest',
    keepIndex: set.photos.reduce((best, p, idx, arr) => (p.size > arr[best].size ? idx : best), 0),
    titleText: `Burst ${i + 1}`,
  }), 'No similar burst photos found.')];
}

function renderSizes() {
  const r = state.result;
  const intro = h('div', { class: 'notice notice-info' },
    'Photos with exactly the same byte size. IDENTICAL means the S3 checksums match, so these are true copies. ',
    'UNVERIFIED means the photos were uploaded in parts and can\'t be compared by checksum; enable SHA-256 verification to confirm.',
    r.hiddenSizeGroups ? ` ${r.hiddenSizeGroups} same-size group(s) are hidden because their checksums prove the content differs.` : '');
  return [intro, ...paged('sizes', r.sizeGroups, (g, i) => groupCard({
    title: `${fmtBytes(g.size)} × ${g.photos.length}`,
    meta: (g.crossBucket ? `Across buckets: ${g.buckets.join(', ')}` : `Within ${g.buckets[0]}`) + ` · ${g.verdictReason}`,
    pills: [[g.verdict, g.verdict === 'IDENTICAL' ? 'pill-danger' : 'pill-warn'],
      [g.crossBucket ? 'Cross-bucket' : 'Same bucket', '']],
    photos: g.photos,
    extra: (p, idx) => `id ${shortId(g.contentIds[idx])}`,
    compareRows: [['Content ID', (p, idx) => g.contentIds[idx] || '—']],
    keepLabel: 'Mark all but first',
    keepIndex: 0,
    titleText: `Same size ${i + 1}`,
  }), 'No same-size photos found.')];
}

function renderNames() {
  const intro = h('div', { class: 'notice notice-info' },
    'The same filename appears in more than one bucket. These may be copies or unrelated photos that happen to share a camera-assigned name. Compare before marking.');
  return [intro, ...paged('names', state.result.nameGroups, (g, i) => groupCard({
    title: g.name,
    meta: `${new Set(g.photos.map((p) => p.bucket)).size} buckets`,
    pills: [[g.sameSize ? 'Same size' : 'Sizes differ', g.sameSize ? 'pill-warn' : '']],
    photos: g.photos,
    extra: () => null,
    compareRows: [],
    keepLabel: 'Mark all but first',
    keepIndex: 0,
    titleText: `Filename ${i + 1}`,
  }), 'No filenames shared across buckets.')];
}

function shortId(id) {
  if (!id) return '?';
  return id.startsWith('sha256:') ? id.slice(0, 15) : id.slice(0, 8);
}

function groupCard(g) {
  const actions = h('div', { class: 'group-actions' },
    h('button', { class: 'btn-sm', onclick: () => openCompare(g) }, 'Compare side by side'),
    h('button', {
      class: 'btn-sm secondary outline',
      title: 'Marks every photo in this group except one, for you to review',
      onclick: () => g.photos.forEach((p, idx) => setMarked(p, idx !== g.keepIndex)),
    }, g.keepLabel),
    h('button', { class: 'btn-sm secondary outline', onclick: () => g.photos.forEach((p) => setMarked(p, false)) }, 'Unmark all'),
  );
  return h('article', { class: 'group', 'aria-label': g.titleText },
    h('header', {},
      h('div', { class: 'group-title' },
        h('h3', {}, g.title, g.pills.map(([text, cls]) => h('span', { class: `pill ${cls}` }, text))),
        h('small', {}, g.meta)),
      actions),
    h('div', { class: 'tiles' }, g.photos.map((p, idx) => tile(p, g.extra(p, idx)))));
}

function preview(uri, size, cls) {
  const img = h('img', { class: cls, src: thumbUrl(uri, size), loading: 'lazy', alt: '' });
  img.addEventListener('error', () => {
    img.replaceWith(h('div', { class: 'no-preview' }, 'No preview (format not viewable in the browser, e.g. HEIC/RAW)'));
  }, { once: true });
  return img;
}

function tile(p, extra) {
  const checkbox = h('input', {
    type: 'checkbox',
    dataset: { uri: p.uri },
    checked: state.marked.has(p.uri),
    onchange: (e) => setMarked(p, e.target.checked),
  });
  return h('figure', { class: `tile${state.marked.has(p.uri) ? ' marked' : ''}`, dataset: { uri: p.uri } },
    preview(p.uri, 320, 'thumb'),
    h('figcaption', {},
      h('span', { class: 'name', title: p.uri }, p.fileName),
      h('span', { class: 'where' }, `${p.bucket}/${p.folder}`),
      h('span', { class: 'facts' }, fmtCapture(p.captureTime)),
      h('span', { class: 'facts' }, `${fmtBytes(p.size)} · ${dims(p)}${extra ? ' · ' + extra : ''}`)),
    h('label', { class: 'mark' }, checkbox, 'Banish'));
}

/** Marks/unmarks a photo everywhere it appears (one photo can be in several groups). */
function setMarked(p, on) {
  if (on) state.marked.set(p.uri, p); else state.marked.delete(p.uri);
  state.report = null;
  for (const el of document.querySelectorAll('[data-uri]')) {
    if (el.dataset.uri !== p.uri) continue;
    if (el.tagName === 'INPUT') el.checked = on;
    else el.classList.toggle('marked', on);
  }
  updateMarkedCount();
}

// ---------- side-by-side comparison ----------

function openCompare(g) {
  const rows = [
    ['Bucket', (p) => p.bucket],
    ['Folder', (p) => p.folder || '(bucket root)'],
    ['File name', (p) => p.fileName],
    ['Size', (p) => `${fmtBytes(p.size)} (${p.size.toLocaleString()} bytes)`],
    ['Captured', (p) => fmtCapture(p.captureTime)],
    ['Camera', (p) => p.camera || '—'],
    ['Dimensions', dims],
    ['Uploaded', (p) => fmtInstant(p.uploaded)],
    ['ETag', (p) => p.eTag + (p.contentEtag ? '' : ' (multipart)')],
    ...g.compareRows,
  ];
  const head = h('tr', {}, h('th', {}, ''), g.photos.map((p, idx) => h('td', { class: state.marked.has(p.uri) ? 'marked' : null, dataset: { uri: p.uri } },
    h('div', {}, h('b', {}, `#${idx + 1}`)),
    preview(p.uri, 900, ''),
    h('label', { class: 'mark' },
      h('input', {
        type: 'checkbox',
        dataset: { uri: p.uri },
        checked: state.marked.has(p.uri),
        onchange: (e) => setMarked(p, e.target.checked),
      }), 'Banish'))));
  const body = rows.map(([label, fn]) => {
    const values = g.photos.map((p, idx) => String(fn(p, idx)));
    const differs = new Set(values).size > 1;
    return h('tr', { class: differs ? 'differs' : '' }, h('th', {}, label), values.map((v) => h('td', {}, v)));
  });
  $('#compare-title').textContent = `Compare: ${g.title}`;
  $('#compare-table').replaceChildren(h('tbody', {}, head, body));
  $('#compare').showModal();
}

// ---------- banish list & removal report ----------

function renderBanish() {
  const marked = [...state.marked.values()];
  if (!marked.length) {
    return [h('p', { class: 'empty' }, 'Nothing marked yet. Tick "Banish" on photos in the other tabs.')];
  }
  const total = marked.reduce((s, p) => s + p.size, 0);
  const out = [
    h('div', { class: 'notice notice-info' },
      'BucketBanish never deletes anything. Build the removal report to get each file\'s bucket, region, folder and ',
      state.info?.consoleLinks ? 'a direct AWS console link' : 'location', ', then delete the files in your S3 provider\'s console.'),
    h('div', { class: 'banish-actions' },
      h('b', {}, `${marked.length} photo(s) · ${fmtBytes(total)}`),
      h('button', { onclick: buildReport }, state.report ? 'Refresh removal report' : 'Build removal report'),
      h('button', { class: 'secondary outline', onclick: downloadCsv }, 'Download CSV checklist'),
      h('button', { class: 'secondary outline', onclick: copyText, id: 'copy-btn' }, 'Copy as text'),
      h('button', { class: 'secondary outline', onclick: () => { state.marked.clear(); state.report = null; render(); } }, 'Clear all')),
    h('p', { id: 'report-error', class: 'error', role: 'alert', hidden: true }),
  ];
  out.push(state.report ? reportView(state.report) : markedList(marked));
  return out;
}

function markedList(marked) {
  const byBucket = new Map();
  for (const p of marked) byBucket.set(p.bucket, [...(byBucket.get(p.bucket) || []), p]);
  return h('div', {}, [...byBucket].sort((a, b) => a[0].localeCompare(b[0])).map(([bucket, photos]) => h('article', { class: 'banish-bucket' },
    h('header', {}, h('h3', {}, bucket), h('small', { class: 'muted' }, `${photos.length} file(s)`)),
    h('ul', { class: 'file-list' }, photos.sort((a, b) => a.key.localeCompare(b.key)).map((p) => h('li', {},
      h('span', { class: 'fname' }, p.key),
      h('span', { class: 'facts' }, fmtBytes(p.size)),
      h('button', { class: 'btn-sm secondary outline', onclick: () => { setMarked(p, false); render(); } }, 'Unmark')))))));
}

function reportView(r) {
  const steps = h('div', { class: 'notice notice-info' }, h('b', {}, 'To remove these files:'),
    h('ol', { class: 'steps' },
      h('li', {}, r.buckets.some((b) => b.consoleUrl) ? 'Open each folder link below (sign in to the AWS console if asked).'
        : 'In your S3 provider\'s web console, open each bucket and folder listed below.'),
      h('li', {}, 'Tick the listed files. The console\'s search box filters a folder by filename prefix.'),
      h('li', {}, 'Choose Delete and confirm.')),
    h('p', {}, h('small', { class: 'muted' }, 'On versioned buckets, deleting adds a delete marker. Old versions remain until removed under "Show versions" or by a lifecycle rule.')));
  const summary = h('p', {}, h('b', {}, `${r.fileCount} file(s), ${fmtBytes(r.totalBytes)}`),
    ` across ${r.buckets.length} bucket(s)`,
    r.missingCount ? h('span', { class: 'missing' }, ` · ${r.missingCount} already gone`) : '',
    h('small', { class: 'muted' }, ` · checked ${fmtInstant(r.generatedAt)} against ${r.store}`));
  const link = (url, text) => (url ? h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, text) : null);
  return h('div', {}, steps, summary, r.buckets.map((b) => h('article', { class: 'banish-bucket' },
    h('header', {}, h('h3', {}, b.bucket), h('small', { class: 'muted' }, `region: ${b.region || 'n/a'}`),
      link(b.consoleUrl, 'Open bucket in console ↗')),
    b.folders.map((f) => h('div', { class: 'banish-folder' },
      h('div', {}, h('span', { class: 'folder-name' }, f.folder || '(bucket root)'), ' ',
        link(f.consoleUrl, 'Open folder ↗')),
      h('ul', { class: 'file-list' }, f.files.map((file) => h('li', {},
        h('span', { class: 'fname' }, file.fileName),
        file.found
          ? h('span', { class: 'facts' }, `${fmtBytes(file.size)} · uploaded ${fmtInstant(file.uploaded)} · ETag ${file.eTag}`)
          : h('span', { class: 'missing' }, 'NOT FOUND (already removed?)'),
        link(file.consoleUrl, 'Open file ↗')))))))));
}

function reportError(e) {
  const el = $('#report-error');
  if (el) { el.textContent = e.message; el.hidden = false; }
}

async function buildReport() {
  try {
    const res = await api('api/report', { method: 'POST', body: JSON.stringify({ uris: [...state.marked.keys()] }) });
    state.report = await res.json();
    render();
  } catch (e) { reportError(e); }
}

async function downloadCsv() {
  try {
    const res = await api('api/report.csv', { method: 'POST', body: JSON.stringify({ uris: [...state.marked.keys()] }) });
    const url = URL.createObjectURL(await res.blob());
    const a = h('a', { href: url, download: 'bucketbanish-removal-report.csv' });
    document.body.append(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (e) { reportError(e); }
}

async function copyText() {
  try {
    if (!state.report) {
      state.report = await (await api('api/report', { method: 'POST', body: JSON.stringify({ uris: [...state.marked.keys()] }) })).json();
    }
    const lines = [`BucketBanish removal report: ${state.report.fileCount} file(s), ${fmtBytes(state.report.totalBytes)}`];
    for (const b of state.report.buckets) {
      lines.push('', `Bucket: ${b.bucket} (region: ${b.region || 'n/a'})${b.consoleUrl ? '  ' + b.consoleUrl : ''}`);
      for (const f of b.folders) {
        lines.push(`  Folder: ${f.folder || '(bucket root)'}${f.consoleUrl ? '  ' + f.consoleUrl : ''}`);
        for (const file of f.files) {
          lines.push(`    - ${file.fileName}  ${file.found ? fmtBytes(file.size) : 'NOT FOUND'}`);
        }
      }
    }
    await navigator.clipboard.writeText(lines.join('\n'));
    render();
    const btn = $('#copy-btn');
    if (btn) btn.textContent = 'Copied';
  } catch (e) { reportError(e); }
}

// ---------- init ----------

$('#scan-form').addEventListener('submit', startScan);
$('#threshold').addEventListener('input', (e) => { $('#threshold-out').textContent = e.target.value; });
$('#compare-close').addEventListener('click', () => $('#compare').close());
$('#compare').addEventListener('close', () => { if (state.tab === 'banish') render(); });
for (const btn of document.querySelectorAll('.tabs button')) {
  btn.addEventListener('click', () => { state.tab = btn.dataset.tab; render(); });
}

api('api/info').then((r) => r.json()).then((info) => {
  state.info = info;
  $('#store').textContent = `Reading from: ${info.store}`;
}).catch(() => { $('#store').textContent = 'Backend not reachable'; });
