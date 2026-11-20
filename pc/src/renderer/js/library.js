// The library: songs, folders/playlists, hidden folders, recently played, and syncing with
// Google Drive folders and folders on this PC.
'use strict';

const api = window.echo;

const L = {
  songs: [], playlists: [], recent: [],
  videoDirs: [], prefs: { grid: false, volume: 0.8, videoBackground: false },
  videoResume: {},
};
const byId = new Map();

function newId() { return Date.now().toString(36) + Math.floor(Math.random() * 46655).toString(36); }

async function loadLibrary() {
  const d = await api.loadLibrary();
  if (d) {
    L.songs = d.songs || [];
    L.playlists = d.playlists || [];
    L.recent = d.recent || [];
    L.videoDirs = d.videoDirs || [];
    L.prefs = Object.assign(L.prefs, d.prefs || {});
    L.videoResume = d.videoResume || {};
  }
  byId.clear();
  for (const s of L.songs) byId.set(s.id, s);
  for (const p of L.playlists) { p.songs = p.songs || []; p.yt = p.yt || []; }
}

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => api.saveLibrary(L), 250);
  if (typeof scheduleSync === 'function' && !SY.busy) scheduleSync(); // share the change with your other device
}
function saveNow() { clearTimeout(saveTimer); return api.saveLibrary(L); }

// ---------- songs ----------
const song = (id) => byId.get(id);
function addSong(s) {
  if (byId.has(s.id)) return false;
  L.songs.push(s);
  byId.set(s.id, s);
  return true;
}
function removeSong(s) {
  const i = L.songs.indexOf(s);
  if (i >= 0) L.songs.splice(i, 1);
  byId.delete(s.id);
  for (const p of L.playlists) { const j = p.songs.indexOf(s.id); if (j >= 0) p.songs.splice(j, 1); }
}
function deleteSongs(list) { for (const s of list) removeSong(s); save(); }

function sortedSongs() {
  const hide = hiddenSongIds();
  return L.songs.filter((s) => !hide.has(s.id))
    .sort((a, b) => (a.title || '').localeCompare(b.title || '', undefined, { sensitivity: 'base', numeric: true }));
}
function searchSongs(q) {
  q = q.trim().toLowerCase();
  if (!q) return [];
  return sortedSongs().filter((s) => (s.title || '').toLowerCase().includes(q) ||
    (s.artist || '').toLowerCase().includes(q) || (s.album || '').toLowerCase().includes(q));
}
function songSubtitle(s) {
  const a = s.artist && s.artist !== '<unknown>' ? s.artist : (s.source === 'drive' ? 'Google Drive' : 'Unknown artist');
  return a;
}

// ---------- folders / playlists ----------
const playlist = (id) => (id ? L.playlists.find((p) => p.id === id) : null);
function newPlaylist(name, parentId) {
  const p = { id: newId(), name, parentId: parentId || null, songs: [], yt: [] };
  L.playlists.push(p);
  save();
  return p;
}
function children(parentId) {
  return L.playlists.filter((p) => {
    const par = p.parentId && playlist(p.parentId) ? p.parentId : null;
    return (parentId || null) === par;
  }).sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base', numeric: true }));
}
const visibleChildren = (parentId) => children(parentId).filter((p) => !p.hidden);
function pathOf(p) {
  const names = [p.name];
  let cur = playlist(p.parentId), guard = 0;
  while (cur && guard++ < 50) { names.unshift(cur.name); cur = playlist(cur.parentId); }
  return names.join(' › ');
}
function isInside(inner, outer) {
  let cur = inner, guard = 0;
  while (cur && guard++ < 50) { if (cur.id === outer.id) return true; cur = playlist(cur.parentId); }
  return false;
}
function isHidden(p) {
  let cur = p, guard = 0;
  while (cur && guard++ < 50) { if (cur.hidden) return true; cur = playlist(cur.parentId); }
  return false;
}
const localPlaylists = () => L.playlists.filter((p) => !p.youtube).sort((a, b) => pathOf(a).localeCompare(pathOf(b)));
const moveTargets = (p) => localPlaylists().filter((t) => !isInside(t, p));
function songsOf(p) { return p.songs.map(song).filter(Boolean); }
function tree(p) {
  const out = [p];
  for (let i = 0; i < out.length && i < 5000; i++) out.push(...children(out[i].id));
  return out;
}
function allSongsIn(p) {
  const seen = new Set(), out = [];
  for (const t of tree(p)) for (const s of songsOf(t)) if (!seen.has(s.id)) { seen.add(s.id); out.push(s); }
  return out;
}
function songsOnlyIn(p) {
  const inside = tree(p);
  const ids = new Set();
  for (const t of inside) t.songs.forEach((i) => ids.add(i));
  for (const o of L.playlists) if (!inside.includes(o)) o.songs.forEach((i) => ids.delete(i));
  return [...ids].map(song).filter(Boolean);
}
function deleteTreeWithSongs(p) {
  const gone = songsOnlyIn(p);
  for (const t of tree(p)) { const i = L.playlists.indexOf(t); if (i >= 0) L.playlists.splice(i, 1); }
  for (const s of gone) removeSong(s);
  save();
  return gone.length;
}
const countTree = (p) => tree(p).length - 1;
function hiddenFolders() { return L.playlists.filter((p) => p.hidden).sort((a, b) => pathOf(a).localeCompare(pathOf(b))); }
function hiddenSongIds() {
  const inHidden = new Set(), inVisible = new Set();
  for (const p of L.playlists) {
    if (p.youtube) continue;
    const target = isHidden(p) ? inHidden : inVisible;
    p.songs.forEach((i) => target.add(i));
  }
  for (const i of inVisible) inHidden.delete(i);
  return inHidden;
}
function subtitleOf(p) {
  if (p.youtube) return 'YouTube · ' + plural(p.yt.length, 'video');
  const sub = visibleChildren(p.id).length;
  const parts = [];
  if (sub) parts.push(plural(sub, 'folder'));
  if (p.songs.length || !sub) parts.push(plural(p.songs.length, 'song'));
  if (p.sync) parts.push(p.sync.kind === 'drive' ? 'Drive' : 'PC folder');
  return parts.join(' · ');
}
function markRecent(id) {
  L.recent = [id, ...L.recent.filter((x) => x !== id)].slice(0, 12);
  save();
}
function recentFolders(n) {
  const out = [];
  for (const id of L.recent) {
    const p = playlist(id);
    if (p && !isHidden(p) && !out.includes(p)) out.push(p);
    if (out.length >= n) return out;
  }
  for (const p of visibleChildren(null)) { if (out.length >= n) break; if (!out.includes(p)) out.push(p); }
  return out;
}

// ---------- syncing a folder tree (Google Drive or a folder on this PC) ----------
// tree node: { id, name, songs: [song objects], children: [nodes] }

function collectSongIds(n, out) { n.songs.forEach((s) => out.add(s.id)); n.children.forEach((c) => collectSongIds(c, out)); }
function collectFolderIds(n, out) { out.add(n.id); n.children.forEach((c) => collectFolderIds(c, out)); }
const syncNode = (kind, id) => L.playlists.find((p) => p.sync && p.sync.kind === kind && p.sync.id === id);

function syncInto(kind, n, parentId, forced, all, rootId) {
  let pl = forced || syncNode(kind, n.id);
  if (!pl) {
    pl = { id: newId(), name: n.name, parentId: parentId || null, songs: [], yt: [], sync: { kind, id: n.id, root: rootId, name: n.name } };
    L.playlists.push(pl);
  }
  pl.sync = Object.assign(pl.sync || {}, { kind, id: n.id, root: rootId });
  if (n.name && n.name !== pl.sync.name) { // renamed at the source: follow it unless you renamed it in Echo
    if (!pl.sync.name || pl.sync.name === pl.name) pl.name = n.name;
    pl.sync.name = n.name;
  }
  const here = new Set();
  for (const s of n.songs) {
    here.add(s.id);
    const old = song(s.id);
    if (!old) addSong(s);
    else if (old.source === s.source && s.source === 'drive') { old.title = s.title; old.artist = s.artist; old.album = s.album; old.uri = s.uri; }
    if (!pl.songs.includes(s.id)) pl.songs.push(s.id);
  }
  pl.songs = pl.songs.filter((id) => !(all.has(id) && !here.has(id)));
  for (const c of n.children) syncInto(kind, c, pl.id, null, all, rootId);
  return pl;
}

/** Makes Echo match the source. complete = false means "don't remove anything" (something couldn't be read). */
function applyTree(kind, rootNode, rootId, parentForNew, complete) {
  const r = { root: null, newSongs: 0, removedSongs: 0, newFolders: 0, removedFolders: 0 };
  const oldNodes = L.playlists.filter((p) => p.sync && p.sync.kind === kind && (p.sync.root === rootId || p.sync.id === rootId));
  const oldSongs = new Set();
  oldNodes.forEach((p) => p.songs.forEach((i) => oldSongs.add(i)));
  const allSongs = new Set(), allFolders = new Set();
  collectSongIds(rootNode, allSongs);
  collectFolderIds(rootNode, allFolders);
  const existingRoot = syncNode(kind, rootId);
  const f0 = L.playlists.length, s0 = L.songs.length;
  r.root = syncInto(kind, rootNode, existingRoot ? existingRoot.parentId : parentForNew, existingRoot, allSongs, rootId);
  r.newFolders = L.playlists.length - f0;
  r.newSongs = L.songs.length - s0;
  if (complete) {
    const gone = [...oldSongs].map(song).filter((s) => s && !allSongs.has(s.id) && s.source === (kind === 'drive' ? 'drive' : 'file'));
    gone.forEach(removeSong);
    r.removedSongs = gone.length;
    for (const p of oldNodes) {
      if (p === r.root || allFolders.has(p.sync.id)) continue;
      for (const c of children(p.id)) c.parentId = p.parentId;
      const i = L.playlists.indexOf(p);
      if (i >= 0) { L.playlists.splice(i, 1); r.removedFolders++; }
    }
  }
  save();
  return r;
}

function summary(r, none) {
  const parts = [];
  if (r.newSongs) parts.push(plural(r.newSongs, 'new song'));
  if (r.removedSongs) parts.push(r.removedSongs + ' removed');
  if (r.newFolders) parts.push(plural(r.newFolders, 'new folder'));
  if (r.removedFolders) parts.push(plural(r.removedFolders, 'folder') + ' removed');
  return parts.length ? parts.join(', ') : none;
}

// ---------- reading sources ----------
const localSongId = (p) => 'f:' + p.toLowerCase();

/** Turns a scanned PC folder into a tree with song details. Only new files are read for tags. */
async function localTree(dir, onProgress) {
  const raw = await api.scanMusic(dir);
  const files = [];
  (function walk(n) { files.push(...n.files); n.children.forEach(walk); })(raw);
  const fresh = files.filter((f) => !byId.has(localSongId(f)));
  const info = new Map();
  for (let i = 0; i < fresh.length; i += 40) {
    const batch = fresh.slice(i, i + 40);
    const tags = await api.tags(batch);
    batch.forEach((f, k) => info.set(f, tags[k]));
    if (onProgress) onProgress(Math.min(fresh.length, i + 40), fresh.length);
  }
  async function conv(n) {
    const node = { id: n.path, name: n.name, songs: [], children: [] };
    for (const f of n.files) {
      const id = localSongId(f);
      const old = byId.get(id);
      if (old) { node.songs.push(old); continue; }
      const t = info.get(f) || {};
      const base = f.split(/[\\/]/).pop().replace(/\.[^.]+$/, '');
      node.songs.push({ id, source: 'file', path: f, uri: await api.fileUrl(f), title: t.title || base,
        artist: t.artist || '', album: t.album || n.name, duration: t.duration || 0, added: Date.now() });
    }
    for (const c of n.children) node.children.push(await conv(c));
    return node;
  }
  return { root: await conv(raw), complete: true, count: files.length };
}

async function readSource(kind, id) {
  if (kind === 'drive') {
    const f = await api.driveRead(id);
    return f.error ? { error: f.error } : { root: f.root, complete: f.complete };
  }
  // a missing folder (e.g. an unplugged USB drive) changes nothing
  if (!(await api.isDir(id))) return { error: 'The folder "' + id + '" isn\'t there right now.' };
  return localTree(id);
}

function syncRoots() {
  const out = new Map();
  for (const p of L.playlists) {
    if (!p.sync) continue;
    const root = p.sync.root || p.sync.id;
    out.set(p.sync.kind + '|' + root, { kind: p.sync.kind, id: root });
  }
  return [...out.values()];
}

let syncing = false;
/** Checks every imported Drive folder and PC folder for changes. */
async function syncAll(quiet) {
  if (syncing) return null;
  syncing = true;
  const total = { newSongs: 0, removedSongs: 0, newFolders: 0, removedFolders: 0 };
  try {
    for (const r of syncRoots()) {
      const src = await readSource(r.kind, r.id);
      if (src.error || !src.root) continue; // offline or moved: change nothing
      if (!syncNode(r.kind, r.id)) continue;
      const res = applyTree(r.kind, src.root, r.id, null, src.complete);
      for (const k of Object.keys(total)) total[k] += res[k];
    }
  } finally { syncing = false; }
  return total;
}

/** Syncs the imported folder that p belongs to. */
async function syncOne(p) {
  if (!p.sync) return;
  const root = p.sync.root || p.sync.id;
  toast('Checking ' + (p.sync.kind === 'drive' ? 'Google Drive' : 'the folder') + '…');
  const src = await readSource(p.sync.kind, root);
  if (src.error) { alertBox('Couldn\'t sync', src.error); return; }
  const r = applyTree(p.sync.kind, src.root, root, null, src.complete);
  toast(summary(r, 'Everything is up to date'));
  refreshView();
}

// ---------- importing ----------
async function importPcFolder() {
  const picked = await api.open({ title: 'Choose a music folder', properties: ['openDirectory', 'multiSelections'] });
  for (const d of picked) {
    toast('Reading ' + d.name + '…', 60000);
    const t = await localTree(d.path, (i, n) => toast(`Reading song details… ${i} of ${n}`, 60000));
    if (!t.count) { toast('No music found in ' + d.name); continue; }
    const r = applyTree('local', t.root, d.path, null, true);
    toast(`Added "${r.root.name}": ${summary(r, 'nothing new')}`);
    openFolder(r.root.id);
  }
}

async function importFiles() {
  const picked = await api.open({ title: 'Choose songs', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Audio', extensions: ['mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'opus', 'wma'] }] });
  if (!picked.length) return;
  const tags = await api.tags(picked.map((f) => f.path));
  const added = [];
  picked.forEach((f, i) => {
    const s = { id: localSongId(f.path), source: 'file', path: f.path, uri: f.url, title: tags[i].title || f.name,
      artist: tags[i].artist || '', album: tags[i].album || '', duration: tags[i].duration || 0, added: Date.now() };
    if (addSong(s)) added.push(s); else added.push(song(s.id));
  });
  save();
  const name = await prompt(plural(added.length, 'song') + ' added', 'Imported ' + new Date().toLocaleDateString(), '',
    'Put them in a new folder? Type its name, or press Cancel to keep them just in Songs.');
  if (name) {
    const p = newPlaylist(name, null);
    p.songs.push(...added.map((s) => s.id));
    save();
    openFolder(p.id);
  } else refreshView();
}

async function importDrive(prefill) {
  const link = await prompt('Google Drive folder', prefill || '', 'https://drive.google.com/drive/folders/…',
    'Paste the link of a Drive folder with your songs. They play online; nothing is downloaded.<br><br>' +
    'In Google Drive: right-click the folder → <b>Share</b> → General access: <b>Anyone with the link</b> → <b>Copy link</b>.');
  if (link == null) return;
  const id = /drive\.google\.com/.test(link) || /^[A-Za-z0-9_-]{20,}$/.test(link) ? (/(?:\/folders\/|[?&]id=)([A-Za-z0-9_-]{10,})/.exec(link) || [])[1] || (/^[A-Za-z0-9_-]{20,}$/.test(link) ? link : null) : null;
  if (!id) { alertBox('Not a folder link', 'That doesn\'t look like a Google Drive folder link. It should contain "/folders/".'); return; }
  toast('Reading the Drive folder…', 60000);
  const f = await api.driveRead(id);
  if (f.error) { toast(''); alertBox('Couldn\'t add the folder', f.error); return; }
  const r = applyTree('drive', f.root, id, null, f.complete);
  toast(`Added "${r.root.name}": ${summary(r, 'nothing new')}`);
  openFolder(r.root.id);
}

async function showImport() {
  const i = await choose('Import music', [
    'A folder on this PC (keeps its sub-folders, stays in sync)',
    'Songs (pick files)',
    'Google Drive folder (plays online, stays in sync)',
    'YouTube playlist',
  ]);
  if (i === 0) importPcFolder();
  else if (i === 1) importFiles();
  else if (i === 2) importDrive();
  else if (i === 3) importYouTube();
}
