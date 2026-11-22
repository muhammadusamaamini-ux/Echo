// Phone ⇄ PC sync. Both Echo apps keep one shared library file, echo/library.json, in your private
// GitHub data repository (like Book Shelf). Music files on a device are uploaded once to echo/files/
// and downloaded by the other device. Google Drive and YouTube items are just links, so nothing is uploaded.
//
// How changes are merged: each device remembers what the library looked like after its last sync
// (a "snapshot"). Anything different now was changed here; anything missing now was removed here.
// The newest change wins.
'use strict';

const SYNC_MAX = 45 * 1024 * 1024; // bigger files aren't uploaded (GitHub's limit)
const SY = { busy: false, state: 'off', text: 'Sync is off', timer: null, cfg: null, lastError: '' };

function syncCfg() { return L.prefs.sync && L.prefs.sync.repo && L.prefs.sync.token ? L.prefs.sync : null; }

function canon(v) {
  if (Array.isArray(v)) return '[' + v.map(canon).join(',') + ']';
  if (v && typeof v === 'object') return '{' + Object.keys(v).filter((k) => v[k] !== undefined && k !== 't').sort().map((k) => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
  return JSON.stringify(v === undefined ? null : v);
}
function hashStr(s) { let h = 2166136261 >>> 0; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; } return h.toString(36) + ':' + s.length; }
const entityHash = (e) => hashStr(canon(e));
function utf8B64(s) { const b = new TextEncoder().encode(s); let x = ''; for (let i = 0; i < b.length; i += 8192) x += String.fromCharCode(...b.subarray(i, i + 8192)); return btoa(x); }
function b64Utf8(b) { const bin = atob(b.replace(/\s/g, '')); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new TextDecoder().decode(u); }

async function ghJson(method, p, body) {
  const c = syncCfg();
  const r = await fetch('https://api.github.com/repos/' + c.repo + p, {
    method, cache: 'no-store',
    headers: { Authorization: 'Bearer ' + c.token, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let j = null;
  try { j = await r.json(); } catch (e) { /* empty */ }
  if (!r.ok) { const err = new Error((j && j.message) || ('GitHub answered ' + r.status)); err.status = r.status; throw err; }
  return j;
}

// ---------- local library → shared entities ----------
function songKey(s) {
  if (s.source === 'drive') return s.id;
  if (s.k) return 'h_' + s.k;
  return null;
}
function songEntity(s) {
  const e = { title: s.title || '', artist: s.artist || '', album: s.album || '', duration: Math.round(s.duration || 0) };
  if (s.source === 'drive') { e.kind = 'drive'; e.uri = s.uri; }
  else { e.kind = 'file'; e.file = s.k; e.ext = (s.path || s.uri || '').split('.').pop().toLowerCase().slice(0, 5) || 'mp3'; e.size = s.size || 0; }
  return e;
}
function folderKey(p) {
  if (p.sync && p.sync.kind === 'drive') return 'drv_' + p.sync.id;
  if (p.youtube && p.ytListId) return 'yt_' + p.ytListId;
  return p.id;
}
function folderEntity(p, keyOfSong) {
  const par = playlist(p.parentId);
  const songs = p.songs.map(keyOfSong).filter(Boolean);
  for (const k of p.pendingKeys || []) if (!songs.includes(k)) songs.push(k);
  const e = { name: p.name, parent: par ? folderKey(par) : null, hidden: !!p.hidden, songs };
  if (p.youtube) { e.youtube = true; e.ytListId = p.ytListId; e.yt = p.yt; }
  if (p.sync) e.src = { kind: p.sync.kind, id: p.sync.id, root: p.sync.root || p.sync.id, name: p.sync.name || '' };
  else if (p.remoteSrc) e.src = p.remoteSrc; // kept for the other device
  return e;
}

/** Makes sure PC music files have a content key (so the same song is recognised on every device). */
async function keyLocalFiles(limitMs) {
  const until = Date.now() + limitMs;
  for (const s of L.songs) {
    if (Date.now() > until) return false;
    if (s.source !== 'file' || s.k || !s.path) continue;
    const size = await api.fileSize(s.path);
    if (size < 0) continue;
    s.size = size;
    s.k = await api.hashFile(s.path);
  }
  return true;
}

function localEntities() {
  const keyOfSong = (id) => { const s = song(id); return s ? songKey(s) : null; };
  const songs = {}, folders = {};
  for (const s of L.songs) { const k = songKey(s); if (k && !s.noSync) songs[k] = songEntity(s); }
  for (const p of L.playlists) folders[folderKey(p)] = folderEntity(p, keyOfSong);
  return { songs, folders };
}

// ---------- applying the other device's changes here ----------
function localSongByKey(k) {
  if (k.startsWith('gd_')) return song(k);
  if (k.startsWith('h_')) return L.songs.find((s) => s.k === k.slice(2)) || null;
  return null;
}
function localFolderByKey(k) { return L.playlists.find((p) => folderKey(p) === k) || null; }

async function applySong(k, e, downloads) {
  if (localSongByKey(k)) { // update details
    const s = localSongByKey(k);
    Object.assign(s, { title: e.title, artist: e.artist, album: e.album });
    if (e.duration) s.duration = e.duration;
    return;
  }
  if (e.kind === 'drive') {
    addSong({ id: k, source: 'drive', uri: e.uri, title: e.title, artist: e.artist, album: e.album, duration: e.duration || 0, added: Date.now() });
  } else if (e.kind === 'file' && e.file && e.uploaded) {
    downloads.push({ k, e });
  }
}

async function runDownloads(list) {
  if (!list.length) return 0;
  const cfg = Object.assign({ branch: SY.branch }, syncCfg());
  const dir = await api.syncMusicDir();
  let n = 0;
  for (const { k, e } of list) {
    setSyncState('busy', `Downloading ${e.title}… (${++n} of ${list.length})`);
    const safe = (e.title || 'Song').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);
    const r = await api.syncDownload(cfg, 'echo/files/' + e.file + '.' + e.ext, dir + '\\' + safe + '.' + e.ext);
    if (!r.ok) continue;
    if (localSongByKey(k)) continue;
    addSong({ id: 'f:' + r.path.toLowerCase(), source: 'file', path: r.path, uri: r.url, k: e.file, size: e.size,
      title: e.title, artist: e.artist, album: e.album, duration: e.duration || 0, added: Date.now() });
    // put it into the folders that were waiting for it
    for (const p of L.playlists) {
      if (p.pendingKeys && p.pendingKeys.includes(k)) {
        p.pendingKeys = p.pendingKeys.filter((x) => x !== k);
        const id = 'f:' + r.path.toLowerCase();
        if (!p.songs.includes(id)) p.songs.push(id);
      }
    }
  }
  return n;
}

function applyFolders(remoteFolders, keys) {
  // first make every folder exist, then set parents and songs (a parent may be new too)
  for (const k of keys) {
    const e = remoteFolders[k];
    let p = localFolderByKey(k);
    if (!p) {
      p = { id: (k.startsWith('drv_') || k.startsWith('yt_')) ? newId() : k, name: e.name, parentId: null, songs: [], yt: [] };
      if (e.src && e.src.kind === 'drive') p.sync = { kind: 'drive', id: e.src.id, root: e.src.root || e.src.id, name: e.src.name || e.name };
      L.playlists.push(p);
    }
  }
  for (const k of keys) {
    const e = remoteFolders[k];
    const p = localFolderByKey(k);
    if (!p) continue;
    p.name = e.name;
    p.hidden = !!e.hidden;
    const par = e.parent ? localFolderByKey(e.parent) : null;
    p.parentId = par && par !== p ? par.id : null;
    if (e.youtube) { p.youtube = true; p.ytListId = e.ytListId; p.yt = e.yt || []; }
    if (e.src && e.src.kind !== 'drive' && !(p.sync && p.sync.kind === e.src.kind)) p.remoteSrc = e.src;
    const ids = [], pending = [];
    for (const sk of e.songs || []) { const s = localSongByKey(sk); if (s) ids.push(s.id); else pending.push(sk); }
    p.songs = ids;
    p.pendingKeys = pending;
  }
}

// ---------- one sync ----------
async function syncNow(manual) {
  const c = syncCfg();
  if (!c) { if (manual) openSyncSettings(); return; }
  if (SY.busy) return;
  SY.busy = true;
  setSyncState('busy', 'Syncing…');
  try {
    if (!SY.branch) { const repo = await ghJson('GET', ''); SY.branch = repo.default_branch || 'main'; }
    await keyLocalFiles(manual ? 120000 : 20000);
    for (let attempt = 0; attempt < 3; attempt++) {
      // 1. the shared file as it is now
      let remote = { v: 1, songs: {}, folders: {}, tomb: {} }, sha = null;
      try {
        const f = await ghJson('GET', '/contents/echo/library.json?ref=' + encodeURIComponent(SY.branch));
        remote = Object.assign(remote, JSON.parse(b64Utf8(f.content)));
        sha = f.sha;
      } catch (e) { if (e.status !== 404) throw e; }
      // 2. what changed here since last time
      const snap = L.prefs.syncSnap || {};
      const local = localEntities();
      const now = Date.now();
      const merged = { v: 1, songs: { ...remote.songs }, folders: { ...remote.folders }, tomb: { ...remote.tomb } };
      const changedHere = new Set();
      for (const kind of ['songs', 'folders']) {
        for (const [k, e] of Object.entries(local[kind])) {
          const had = snap[k];
          // changed here = edited since the last sync, or new here and not known to the shared library
          const changed = had ? had.h !== entityHash(e) : !remote[kind][k];
          if (!changed) continue;
          changedHere.add(k);
          merged[kind][k] = Object.assign({}, e, { t: now });
          delete merged.tomb[k];
        }
      }
      for (const k of Object.keys(snap)) {
        if (!local.songs[k] && !local.folders[k]) { delete merged.songs[k]; delete merged.folders[k]; merged.tomb[k] = now; } // removed here
      }
      // keep the upload flags the devices set
      for (const [k, e] of Object.entries(merged.songs)) {
        const r = remote.songs[k];
        if (r && e.kind === 'file') { if (r.uploaded) e.uploaded = true; if (r.tooBig) e.tooBig = true; }
      }
      // 4. upload music files the other device doesn't have yet
      const cfg = Object.assign({ branch: SY.branch }, c);
      const toUpload = L.songs.filter((s) => s.source === 'file' && s.k && s.path && !s.noSync && merged.songs['h_' + s.k] && !merged.songs['h_' + s.k].uploaded);
      let up = 0;
      for (const s of toUpload) {
        if ((s.size || 0) > SYNC_MAX) { merged.songs['h_' + s.k].tooBig = true; continue; }
        setSyncState('busy', `Uploading ${s.title}… (${++up} of ${toUpload.length})`);
        const r = await api.syncUpload(cfg, 'echo/files/' + s.k + '.' + merged.songs['h_' + s.k].ext, s.path);
        if (r.ok) merged.songs['h_' + s.k].uploaded = true;
      }
      // 5. save the shared file (if someone else saved in between, start again)
      const body = JSON.stringify(merged);
      const changedRemote = canon(merged) !== canon(remote) || toUpload.length;
      if (changedRemote) {
        try {
          await ghJson('PUT', '/contents/echo/library.json', { message: 'Echo sync', content: utf8B64(body), branch: SY.branch, ...(sha ? { sha } : {}) });
        } catch (e) {
          if (e.status === 409 || e.status === 422) continue; // changed meanwhile: merge again
          throw e;
        }
      }
      // 6. bring the other device's changes here
      const downloads = [];
      const removedHere = (k) => snap[k] && !local.songs[k] && !local.folders[k];
      for (const [k, e] of Object.entries(merged.songs)) {
        if (changedHere.has(k) || removedHere(k)) continue;
        const mine = local.songs[k];
        if (!mine || entityHash(mine) !== entityHash(e)) await applySong(k, e, downloads);
      }
      const folderKeys = Object.keys(merged.folders).filter((k) => !changedHere.has(k) && !removedHere(k) &&
        !(local.folders[k] && entityHash(local.folders[k]) === entityHash(merged.folders[k])));
      applyFolders(merged.folders, folderKeys);
      for (const [k, t] of Object.entries(merged.tomb)) {
        if (changedHere.has(k)) continue;
        if (snap[k] && snap[k].t > t) continue;
        const s2 = localSongByKey(k);
        if (s2) removeSong(s2);
        const p = localFolderByKey(k);
        if (p) { for (const ch of children(p.id)) ch.parentId = p.parentId; L.playlists.splice(L.playlists.indexOf(p), 1); }
      }
      const got = await runDownloads(downloads);
      // 7. remember how things look now
      const after = localEntities();
      const newSnap = {};
      for (const kind of ['songs', 'folders']) for (const [k, e] of Object.entries(after[kind])) newSnap[k] = { h: entityHash(e), t: (merged[kind][k] && merged[kind][k].t) || now };
      L.prefs.syncSnap = newSnap;
      L.prefs.syncLast = Date.now();
      save();
      if (canon(after) !== canon(local)) refreshView(); // only when something actually came from your other device
      setSyncState('ok', 'Synced ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
      if (manual) toast('Synced' + (got ? ` · ${plural(got, 'song')} downloaded` : ''));
      break;
    }
  } catch (e) {
    SY.lastError = e.message;
    setSyncState('err', e.status === 401 ? 'Sync: token expired' : navigator.onLine ? 'Sync paused' : 'Offline');
    if (manual) alertBox('Sync didn\'t work', e.status === 401 ? 'GitHub didn\'t accept the token. Open Sync and paste a new one.' : e.message);
  } finally {
    SY.busy = false;
  }
}

function setSyncState(state, text) {
  SY.state = state; SY.text = text;
  const b = $('#syncBtn');
  if (!b) return;
  b.lastChild.textContent = text;
  b.style.color = state === 'err' ? '#ff9db0' : state === 'ok' ? 'var(--glow)' : '';
}

/** Sync a little while after the library changes, and every few minutes. */
function scheduleSync(delay = 20000) {
  if (!syncCfg()) return;
  clearTimeout(SY.timer);
  SY.timer = setTimeout(() => syncNow(false), delay);
}

// ---------- settings ----------
async function openSyncSettings() {
  const c = syncCfg();
  const body = el('div');
  if (c) {
    body.appendChild(el('p', { html: `Connected to <b>${esc(c.repo)}</b>. Your folders, playlists and songs are kept the same on your phone and this PC.<br><br>Status: <b>${esc(SY.text)}</b>` +
      (SY.lastError && SY.state === 'err' ? `<br><span class="dim">${esc(SY.lastError)}</span>` : '') }));
    const v = await dialog({ title: 'Sync between your phone and PC', body,
      buttons: [{ text: 'Turn off', danger: true, value: 'off' }, { text: 'Close', value: false }, { text: 'Sync now', primary: true, value: 'now' }] });
    if (v === 'now') syncNow(true);
    else if (v === 'off' && await confirmBox('Turn off sync', 'Echo on this PC stops syncing. Nothing is deleted.', 'Turn off')) {
      delete L.prefs.sync; save(); setSyncState('off', 'Sync is off');
    }
    return;
  }
  body.innerHTML = `<p>Sync keeps one small library file, plus the music files from your devices, in a <b>private</b> GitHub repository only you can see, the same way Book Shelf does.</p>
    <ol style="padding-left:20px;line-height:1.7">
      <li>Create a <b>private</b> repository named <b>Echo-Data</b>: <a href="#" data-url="https://github.com/new?name=Echo-Data&visibility=private">github.com/new</a></li>
      <li>Make a token: <a href="#" data-url="https://github.com/settings/personal-access-tokens/new">github.com/settings/personal-access-tokens/new</a> → Repository access: <b>Only select repositories → Echo-Data</b> → Permissions → <b>Contents: Read and write</b> → Generate, and copy it.</li>
      <li>Paste it below. On your phone: Echo → ⋮ → <b>Sync</b>, and paste the same token.</li>
    </ol>`;
  $$('a[data-url]', body).forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); api.openExternal(a.dataset.url); }));
  const repo = el('input', { type: 'text', value: 'muhammadusamaamini-ux/Echo-Data', spellcheck: 'false' });
  const tok = el('input', { type: 'text', placeholder: 'github_pat_…', spellcheck: 'false' });
  const msg = el('div.dim', { style: { marginTop: '10px' } });
  body.append(el('div.label', 'Data repository'), repo, el('div.label', 'Token'), tok, msg);
  await dialog({ title: 'Sync between your phone and PC', body, buttons: [{ text: 'Cancel', value: false }, { text: 'Connect', primary: true, value: true,
    check: async () => {
      const r = repo.value.trim().replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '').replace(/\/$/, '');
      const t = tok.value.trim();
      if (!/^[\w.-]+\/[\w.-]+$/.test(r) || !t) { msg.textContent = 'Enter the repository as owner/name and paste the token.'; return false; }
      msg.textContent = 'Checking…';
      try {
        const res = await fetch('https://api.github.com/repos/' + r, { headers: { Authorization: 'Bearer ' + t, Accept: 'application/vnd.github+json' }, cache: 'no-store' });
        const j = await res.json().catch(() => ({}));
        if (res.status === 401) { msg.textContent = 'GitHub didn\'t accept this token. Copy it again and paste it.'; return false; }
        if (res.status === 404) { msg.textContent = 'That repository wasn\'t found, or the token isn\'t allowed to use it.'; return false; }
        if (j.permissions && !j.permissions.push) { msg.textContent = 'The token can read but not write. Set Contents to "Read and write".'; return false; }
        if (!j.private && !(await confirmBox('This repository is public', 'Anyone could see your library and music. Use a private repository?', 'Use it anyway', true))) return false;
        L.prefs.sync = { repo: r, token: t };
        L.prefs.syncSnap = {};
        SY.branch = j.default_branch || 'main';
        save();
        setTimeout(() => syncNow(true), 100);
        return true;
      } catch (e) { msg.textContent = 'Couldn\'t reach GitHub. Check your internet.'; return false; }
    } }] });
}
