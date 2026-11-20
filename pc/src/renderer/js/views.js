// Screens: Home, Library, a folder, Songs, Search, a YouTube playlist.
'use strict';

const main = $('#main');
const NAV = { view: 'home', arg: null };
const SEL = { ids: new Set(), list: [], anchor: -1, folder: null };

function go(view, arg) {
  if (typeof leavingEditor === 'function' && !leavingEditor(view)) return;
  NAV.view = view; NAV.arg = arg;
  clearSelection(true);
  $$('#side .nav[data-go]').forEach((n) => n.classList.toggle('on', n.dataset.go === view));
  render();
  main.scrollTop = 0;
}
function openFolder(id) {
  const p = playlist(id);
  if (!p) return;
  markRecent(id);
  if (p.youtube) go('youtube', id); else go('folder', id);
}
function refreshView() { const top = main.scrollTop; render(); main.scrollTop = top; renderSideFolders(); }

function render() {
  main.innerHTML = '';
  main.classList.remove('webmode');
  const v = NAV.view;
  if (v === 'home') homeView();
  else if (v === 'library') libraryView();
  else if (v === 'folder') folderView(playlist(NAV.arg));
  else if (v === 'songs') songsView();
  else if (v === 'search') searchView();
  else if (v === 'youtube') youtubeView(playlist(NAV.arg));
  else if (v === 'videos') videosView(NAV.arg);
  else if (v === 'veditor') videoEditorView(NAV.arg);
  else if (v === 'aeditor') audioEditorView(NAV.arg);
  else if (v === 'ytweb') ytWebView();
  markPlaying();
}

function head(title, ...actions) {
  return el('div.head', el('h1', title), ...actions);
}
function viewToggle() {
  return iconBtn(L.prefs.grid ? 'list' : 'grid', L.prefs.grid ? 'List view' : 'Tile view', () => {
    L.prefs.grid = !L.prefs.grid; save(); refreshView();
  });
}
function greeting() {
  const h = new Date().getHours();
  return h >= 4 && h < 12 ? 'Good morning' : h < 17 && h >= 12 ? 'Good afternoon' : 'Good evening';
}

// ---------- folder visuals ----------
function folderIcon(p) {
  const sub = visibleChildren(p.id).length;
  return p.youtube ? 'yt' : (sub || !p.songs.length) ? 'folder' : 'music';
}
function folderArt(p, node) {
  node.style.background = p.youtube ? YT_GRAD : grad(p.name);
  node.appendChild(icon(folderIcon(p)));
  return node;
}
function folderCard(p) {
  const c = el('div.card', { draggable: 'true', onclick: () => openFolder(p.id), oncontextmenu: (e) => folderMenu(e, p) },
    folderArt(p, el('div.art')),
    el('div.name', p.name), el('div.meta', subtitleOf(p)),
    el('button.icon-btn.more', { title: 'More', onclick: (e) => folderMenu(e, p) }, icon('more')));
  dragFolder(c, p);
  return c;
}
function folderRow(p) {
  const r = el('div.item.folder', { draggable: 'true', onclick: () => openFolder(p.id), oncontextmenu: (e) => folderMenu(e, p) },
    el('div.num', ''), folderArt(p, el('div.ico')),
    el('div', el('div.title', p.name), el('div.s', subtitleOf(p))),
    el('button.icon-btn', { title: 'More', onclick: (e) => folderMenu(e, p) }, icon('more')));
  dragFolder(r, p);
  return r;
}
function addFolderCard(parentId) {
  return el('div.card.add', { onclick: () => newFolder(parentId) },
    el('div.art', icon('add')), el('div.name', 'New folder'), el('div.meta', 'Make a folder or playlist'));
}
function foldersBlock(list, parentId, withAdd) {
  if (L.prefs.grid) {
    const g = el('div.cards', list.map(folderCard));
    if (withAdd) g.appendChild(addFolderCard(parentId));
    return g;
  }
  const l = el('div.list', list.map(folderRow));
  if (withAdd) l.appendChild(el('div.item.folder', { onclick: () => newFolder(parentId) }, el('div.num'),
    el('div.ico', { style: { background: 'rgba(255,255,255,.08)' } }, icon('add')), el('div.title', 'New folder'), el('div')));
  return l;
}

// ---------- drag & drop (songs → folders, folders → folders) ----------
function dragFolder(node, p) {
  node.addEventListener('dragstart', (e) => { e.dataTransfer.setData('echo/folder', p.id); e.dataTransfer.effectAllowed = 'move'; });
  if (p.youtube) return;
  node.addEventListener('dragover', (e) => {
    if (e.dataTransfer.types.includes('echo/songs') || e.dataTransfer.types.includes('echo/folder')) { e.preventDefault(); node.style.outline = '2px solid var(--glow)'; }
  });
  node.addEventListener('dragleave', () => { node.style.outline = ''; });
  node.addEventListener('drop', (e) => {
    e.preventDefault();
    node.style.outline = '';
    const ids = e.dataTransfer.getData('echo/songs');
    const fid = e.dataTransfer.getData('echo/folder');
    if (ids) {
      const list = ids.split('\n');
      let n = 0;
      for (const id of list) if (!p.songs.includes(id)) { p.songs.push(id); n++; }
      save(); toast(`${plural(n, 'song')} added to ${p.name}`); refreshView();
    } else if (fid && fid !== p.id) {
      const f = playlist(fid);
      if (!f || isInside(p, f)) { toast('A folder can\'t go inside itself'); return; }
      f.parentId = p.id; save(); toast(`Moved "${f.name}" into "${p.name}"`); refreshView();
    }
  });
}

// ---------- song visuals & selection ----------
function songNode(s, i, list, ctx) {
  const sel = SEL.ids.has(s.id);
  let n;
  if (L.prefs.grid) {
    n = el('div.card' + (sel ? '.sel' : ''), { 'data-song': s.id },
      el('div.art.letter', { style: { background: grad(s.title) } }, firstLetter(s.title)),
      el('div.name.title', s.title), el('div.meta', songSubtitle(s)),
      el('button.icon-btn.more', { onclick: (e) => songMenu(e, s, ctx) }, icon('more')));
  } else {
    n = el('div.item' + (sel ? '.sel' : ''), { 'data-song': s.id },
      el('div.num', String(i + 1)),
      el('div.ico', { style: { background: grad(s.title) } }, firstLetter(s.title)),
      el('div', { style: { minWidth: 0 } }, el('div.title', s.title), el('div.s', songSubtitle(s))),
      el('div.album', s.album || ''),
      el('div.dur', fmtTime(s.duration)),
      el('button.icon-btn', { onclick: (e) => songMenu(e, s, ctx) }, icon('more')));
  }
  n.draggable = true;
  n.addEventListener('dragstart', (e) => {
    const ids = SEL.ids.has(s.id) ? [...SEL.ids] : [s.id];
    e.dataTransfer.setData('echo/songs', ids.join('\n'));
  });
  n.addEventListener('click', (e) => {
    if (e.target.closest('button')) return;
    if (e.ctrlKey || e.metaKey) toggleSel(s, i, list);
    else if (e.shiftKey && SEL.anchor >= 0) rangeSel(i, list);
    else if (SEL.ids.size) toggleSel(s, i, list);
    else playList(list, i);
  });
  n.addEventListener('contextmenu', (e) => songMenu(e, s, ctx));
  return n;
}
function songsBlock(list, ctx) {
  SEL.list = list;
  if (!list.length) return el('div.empty', ctx && ctx.empty || 'No songs here yet.');
  if (L.prefs.grid) return el('div.cards', list.map((s, i) => songNode(s, i, list, ctx)));
  return el('div.list',
    el('div.list-head', el('span', '#'), el('span'), el('span', 'Title'), el('span', 'Album'), el('span', { style: { textAlign: 'right' } }, 'Length'), el('span')),
    list.map((s, i) => songNode(s, i, list, ctx)));
}
function toggleSel(s, i, list) {
  if (SEL.ids.has(s.id)) SEL.ids.delete(s.id); else SEL.ids.add(s.id);
  SEL.anchor = i; SEL.list = list;
  selectionChanged();
}
function rangeSel(i, list) {
  const [a, b] = [Math.min(SEL.anchor, i), Math.max(SEL.anchor, i)];
  for (let k = a; k <= b; k++) SEL.ids.add(list[k].id);
  selectionChanged();
}
function clearSelection(silent) { SEL.ids.clear(); SEL.anchor = -1; if (!silent) selectionChanged(); }
function selectedSongs() { return SEL.list.filter((s) => SEL.ids.has(s.id)); }
function selectionChanged() {
  $$('[data-song]').forEach((n) => n.classList.toggle('sel', SEL.ids.has(n.dataset.song)));
  let bar = $('#selbar');
  if (!SEL.ids.size) { if (bar) bar.remove(); return; }
  if (!bar) { bar = el('div#selbar'); main.prepend(bar); }
  bar.innerHTML = '';
  const f = SEL.folder;
  bar.append(...[
    el('b', plural(SEL.ids.size, 'song') + ' selected'), el('div.grow'),
    el('button.btn.small', { onclick: () => { SEL.list.forEach((s) => SEL.ids.add(s.id)); selectionChanged(); } }, 'Select all (Ctrl+A)'),
    el('button.btn.small', { onclick: () => playList(selectedSongs(), 0) }, 'Play'),
    el('button.btn.small', { onclick: () => addToQueue(selectedSongs()) }, 'Add to queue'),
    el('button.btn.small', { onclick: () => addToFolder(selectedSongs(), null, false) }, f ? 'Copy to folder' : 'Add to folder'),
    f ? el('button.btn.small', { onclick: () => addToFolder(selectedSongs(), f, true) }, 'Move to folder') : null,
    f ? el('button.btn.small', { onclick: () => { const ids = new Set(SEL.ids); f.songs = f.songs.filter((x) => !ids.has(x)); save(); clearSelection(); refreshView(); } }, 'Remove from folder') : null,
    el('button.btn.small.danger', { onclick: () => removeFromEcho(selectedSongs()) }, 'Remove from Echo'),
    iconBtn('close', 'Clear selection (Esc)', () => clearSelection())].filter(Boolean));
}

// ---------- menus & actions ----------
function songMenu(e, s, ctx) {
  const many = SEL.ids.has(s.id) && SEL.ids.size > 1 ? selectedSongs() : [s];
  const f = ctx && ctx.folder;
  menu(e, [
    ['Play', () => playList(many, 0)],
    ['Play next', () => many.slice().reverse().forEach(playNextSong)],
    ['Add to queue', () => addToQueue(many)],
    '-',
    ['Add to folder…', () => addToFolder(many, null, false)],
    f ? ['Move to folder…', () => addToFolder(many, f, true)] : null,
    f ? ['Remove from this folder', () => { const ids = new Set(many.map((x) => x.id)); f.songs = f.songs.filter((x) => !ids.has(x)); save(); clearSelection(true); refreshView(); }] : null,
    f && many.length === 1 ? ['Move up', () => moveInFolder(f, s, -1)] : null,
    f && many.length === 1 ? ['Move down', () => moveInFolder(f, s, 1)] : null,
    '-',
    many.length === 1 ? ['Edit audio (cut, trim…)', () => go('aeditor', { songs: [s] })] : ['Edit audio (join these)', () => go('aeditor', { songs: many })],
    s.source === 'file' && many.length === 1 ? ['Show in folder', () => api.showInFolder(s.path)] : null,
    s.source === 'drive' && many.length === 1 ? ['Open in Google Drive', () => api.openExternal('https://drive.google.com/file/d/' + s.id.slice(3) + '/view')] : null,
    ['Search the web for this', () => api.openExternal('https://www.google.com/search?q=' + encodeURIComponent(s.title + ' ' + (s.artist || '')))],
    '-',
    ['Remove from Echo', () => removeFromEcho(many)],
  ]);
}
function moveInFolder(f, s, d) {
  const i = f.songs.indexOf(s.id), j = i + d;
  if (i < 0 || j < 0 || j >= f.songs.length) return;
  f.songs.splice(i, 1); f.songs.splice(j, 0, s.id); save(); refreshView();
}

async function addToFolder(songs, from, move) {
  const targets = localPlaylists().filter((p) => p !== from);
  const names = ['+ New folder', ...targets.map(pathOf)];
  const i = await choose((move ? 'Move ' : 'Add ') + plural(songs.length, 'song') + ' to', names);
  if (i < 0) return;
  let t;
  if (i === 0) { const n = await prompt('New folder', 'My playlist'); if (!n) return; t = newPlaylist(n, null); }
  else t = targets[i - 1];
  for (const s of songs) if (!t.songs.includes(s.id)) t.songs.push(s.id);
  if (move && from) { const ids = new Set(songs.map((s) => s.id)); from.songs = from.songs.filter((x) => !ids.has(x)); }
  save(); clearSelection(true);
  toast((move ? 'Moved to ' : 'Added to ') + t.name);
  refreshView();
}

async function removeFromEcho(songs) {
  const drive = songs.some((s) => s.source === 'drive'), file = songs.some((s) => s.source === 'file');
  let msg = `Remove ${songs.length === 1 ? '"' + songs[0].title + '"' : songs.length + ' songs'} from Echo? They are taken out of every folder.`;
  if (file) msg += '\n\nThe files stay on your PC.';
  if (drive) msg += '\n\nSongs from Google Drive stay in your Drive.';
  if (!(await confirmBox('Remove from Echo', msg, 'Remove', true))) return;
  deleteSongs(songs);
  clearSelection(true);
  refreshView();
}

async function newFolder(parentId) {
  const n = await prompt(parentId ? 'New folder inside' : 'New folder', 'New folder');
  if (!n) return;
  const p = newPlaylist(n, parentId);
  refreshView();
  openFolder(p.id);
}

function folderMenu(e, p) {
  menu(e, [
    p.youtube ? null : ['Play all', () => playList(allSongsIn(p), 0, false)],
    p.youtube ? null : ['Shuffle all', () => playList(allSongsIn(p), 0, true)],
    p.sync ? ['Sync now', () => syncOne(p)] : null,
    p.youtube ? null : ['New folder inside', () => newFolder(p.id)],
    '-',
    ['Rename', async () => { const n = await prompt('Rename', p.name); if (n) { p.name = n; save(); refreshView(); } }],
    ['Move to…', () => moveFolder(p)],
    [p.hidden ? 'Unhide' : 'Hide', () => { p.hidden = !p.hidden; save(); toast(p.hidden ? 'Hidden. Find it in Library → Hidden folders.' : 'Shown again'); refreshView(); }],
    p.sync && p.sync.kind === 'local' ? ['Show folder on this PC', () => api.openPath(p.sync.id)] : null,
    '-',
    ['Delete', () => deleteFolder(p)],
  ]);
}
async function moveFolder(p) {
  const targets = moveTargets(p);
  const i = await choose(`Move "${p.name}" to`, ['⌂  Library (top level)', ...targets.map(pathOf)]);
  if (i < 0) return;
  p.parentId = i === 0 ? null : targets[i - 1].id;
  save(); toast('Moved'); refreshView();
}
async function deleteFolder(p) {
  const inside = countTree(p), songs = p.youtube ? 0 : songsOnlyIn(p).length;
  let msg = `This removes "${p.name}" from Echo`;
  if (inside || songs) msg += ' together with ' + [inside ? plural(inside, 'folder') : '', songs ? plural(songs, 'song') : ''].filter(Boolean).join(' and ') + ' inside it';
  msg += '.\n\nSongs that are also in your other folders stay there. Nothing is deleted from your PC or Google Drive' + (p.sync ? ', and Echo stops syncing this folder' : '') + '.';
  if (!(await confirmBox('Delete folder', msg, 'Delete', true))) return;
  const n = deleteTreeWithSongs(p);
  toast(`Deleted "${p.name}"` + (n ? ` and ${plural(n, 'song')}` : ''));
  if (NAV.view === 'folder' && !playlist(NAV.arg)) go('library'); else refreshView();
}

async function showHidden() {
  const h = hiddenFolders();
  if (!h.length) { alertBox('Hidden folders', 'Nothing is hidden. To hide a folder, right-click it (or ⋮) and choose Hide.'); return; }
  const i = await choose('Hidden folders', h.map(pathOf), 'Click one to open it. To show it again, open its ⋮ menu and choose Unhide.');
  if (i >= 0) openFolder(h[i].id);
}

// ---------- Home ----------
function homeView() {
  const recent = recentFolders(6), top = visibleChildren(null);
  const h = el('div.head', el('h1.grad', greeting()),
    el('button.btn', { onclick: showImport }, '+ Import music'), el('button.btn', { onclick: () => newFolder(null) }, 'New folder'));
  main.appendChild(h);
  if (!top.length && !L.songs.length) {
    main.appendChild(el('div.empty', { html: 'Welcome to <b>Echo</b> for Windows.<br><br>Add music from a <b>folder on this PC</b>, a <b>Google Drive</b> folder or a <b>YouTube playlist</b>.<br>Play your videos in <b>Videos</b>, and edit with the <b>Video editor</b> and <b>Audio editor</b>.' }),
      el('div.row', { style: { justifyContent: 'center' } }, el('button.btn.primary', { onclick: showImport }, 'Import music')));
    return;
  }
  if (recent.length) {
    main.appendChild(el('h2', 'Recently played'));
    main.appendChild(el('div.recent', recent.map((p) => el('div.t.glass', { onclick: () => openFolder(p.id), oncontextmenu: (e) => folderMenu(e, p) },
      folderArt(p, el('div.ico', { style: { display: 'flex', alignItems: 'center', justifyContent: 'center' } })), el('b', p.name)))));
  }
  main.appendChild(el('h2', 'Your folders'));
  main.appendChild(el('div.cards', top.map(folderCard), addFolderCard(null)));
  const songs = sortedSongs();
  if (songs.length) {
    main.appendChild(el('h2', 'Recently added'));
    const recentSongs = songs.slice().sort((a, b) => (b.added || 0) - (a.added || 0)).slice(0, 8);
    const keep = L.prefs.grid; L.prefs.grid = false;
    main.appendChild(songsBlock(recentSongs, {}));
    L.prefs.grid = keep;
  }
}

// ---------- Library ----------
let libFilter = 'all';
function libraryView() {
  main.appendChild(head('Library', viewToggle(), el('button.btn', { onclick: showHidden }, 'Hidden folders'),
    el('button.btn', { onclick: showImport }, '+ Import'), el('button.btn.primary', { onclick: () => newFolder(null) }, 'New folder')));
  const chips = el('div.row', { style: { marginBottom: '16px' } });
  for (const [k, t] of [['all', 'All'], ['local', 'PC folders'], ['drive', 'Google Drive'], ['youtube', 'YouTube'], ['own', 'Your playlists']]) {
    chips.appendChild(el('div.chip' + (libFilter === k ? '.on' : ''), { onclick: () => { libFilter = k; refreshView(); } }, t));
  }
  main.appendChild(chips);
  let list = visibleChildren(null);
  if (libFilter === 'local') list = L.playlists.filter((p) => p.sync && p.sync.kind === 'local' && (p.sync.root === p.sync.id) && !isHidden(p));
  else if (libFilter === 'drive') list = L.playlists.filter((p) => p.sync && p.sync.kind === 'drive' && (p.sync.root === p.sync.id) && !isHidden(p));
  else if (libFilter === 'youtube') list = L.playlists.filter((p) => p.youtube && !isHidden(p));
  else if (libFilter === 'own') list = L.playlists.filter((p) => !p.sync && !p.youtube && !isHidden(p));
  if (!list.length && libFilter !== 'all') { main.appendChild(el('div.empty', 'Nothing here yet.')); return; }
  main.appendChild(foldersBlock(list, null, libFilter === 'all'));
}

// ---------- a folder ----------
function folderView(p) {
  if (!p) { go('library'); return; }
  SEL.folder = p;
  const crumbs = el('div.crumbs', el('a', { onclick: () => go('library') }, 'Library'));
  let cur = playlist(p.parentId);
  const chain = [];
  while (cur) { chain.unshift(cur); cur = playlist(cur.parentId); }
  chain.forEach((c) => crumbs.append(' › ', el('a', { onclick: () => openFolder(c.id) }, c.name)));
  main.appendChild(crumbs);
  main.appendChild(head(p.name, viewToggle(), iconBtn('more', 'More', (e) => folderMenu(e, p))));
  main.appendChild(el('div.sub', { style: { marginTop: '-8px', marginBottom: '14px' } }, subtitleOf(p) + (p.hidden ? ' · hidden' : '')));
  main.appendChild(el('div.row.wrap', { style: { marginBottom: '8px' } },
    el('button.btn.primary', { onclick: () => playList(allSongsIn(p), 0, false) }, '▶  Play'),
    el('button.btn', { onclick: () => playList(allSongsIn(p), 0, true) }, 'Shuffle'),
    el('button.btn', { onclick: () => addSongsDialog(p) }, '+ Add songs'),
    el('button.btn', { onclick: () => newFolder(p.id) }, 'New folder here'),
    p.sync ? el('button.btn', { onclick: () => syncOne(p) }, 'Sync now') : null));
  const subs = visibleChildren(p.id);
  if (subs.length) { main.appendChild(el('h2', 'Folders')); main.appendChild(foldersBlock(subs, p.id, false)); }
  const songs = songsOf(p);
  if (songs.length || !subs.length) {
    if (subs.length) main.appendChild(el('h2', 'Songs'));
    main.appendChild(songsBlock(songs, { folder: p, empty: 'This folder is empty. Use "+ Add songs" or drag songs here.' }));
  }
}

async function addSongsDialog(p) {
  const all = sortedSongs();
  if (!all.length) { toast('Your library is empty. Import some music first.'); return; }
  const chosen = new Set(p.songs);
  const filter = el('input', { type: 'text', placeholder: 'Filter…' });
  const box = el('div', { style: { maxHeight: '50vh', overflowY: 'auto', marginTop: '10px' } });
  function draw() {
    box.innerHTML = '';
    const q = filter.value.toLowerCase();
    for (const s of all) {
      if (q && !(s.title + ' ' + s.artist).toLowerCase().includes(q)) continue;
      const cb = el('input', { type: 'checkbox' });
      cb.checked = chosen.has(s.id);
      cb.onchange = () => { if (cb.checked) chosen.add(s.id); else chosen.delete(s.id); };
      box.appendChild(el('label.opt', { style: { display: 'flex', gap: '10px', alignItems: 'center' } }, cb,
        el('span', s.title, el('span.dim', s.artist ? '  —  ' + s.artist : ''))));
    }
  }
  filter.oninput = draw;
  draw();
  const ok = await dialog({ title: 'Add songs to ' + p.name, body: el('div', filter, box), wide: true,
    buttons: [{ text: 'Cancel', value: false }, { text: 'Done', primary: true, value: true }] });
  if (!ok) return;
  p.songs = p.songs.filter((id) => chosen.has(id));
  for (const id of chosen) if (!p.songs.includes(id)) p.songs.push(id);
  save(); refreshView();
}

// ---------- Songs ----------
let songSort = 'title';
function songsView() {
  SEL.folder = null;
  const all = sortedSongs();
  main.appendChild(head('Songs', viewToggle(), el('button.btn', { onclick: showImport }, '+ Import'),
    all.length ? el('button.btn.primary', { onclick: () => playList(all, 0, true) }, 'Shuffle all') : null));
  const sorts = el('div.row', { style: { marginBottom: '12px' } }, el('span.dim', 'Sort:'));
  for (const [k, t] of [['title', 'Title'], ['artist', 'Artist'], ['album', 'Album'], ['added', 'Recently added'], ['duration', 'Length']]) {
    sorts.appendChild(el('div.chip' + (songSort === k ? '.on' : ''), { onclick: () => { songSort = k; refreshView(); } }, t));
  }
  main.appendChild(sorts);
  const by = {
    title: (a, b) => (a.title || '').localeCompare(b.title || ''),
    artist: (a, b) => (a.artist || '~').localeCompare(b.artist || '~') || (a.title || '').localeCompare(b.title || ''),
    album: (a, b) => (a.album || '~').localeCompare(b.album || '~'),
    added: (a, b) => (b.added || 0) - (a.added || 0),
    duration: (a, b) => (b.duration || 0) - (a.duration || 0),
  };
  main.appendChild(el('div.sub', { style: { marginBottom: '10px' } }, plural(all.length, 'song') + ' · click to play, Ctrl+click to select several, drag onto a folder to add'));
  main.appendChild(songsBlock(all.sort(by[songSort]), { empty: 'No songs yet. Use "+ Import" to add music.' }));
}

// ---------- Search ----------
let lastQuery = '';
function searchView() {
  SEL.folder = null;
  main.appendChild(head('Search'));
  const input = el('input.field', { type: 'text', placeholder: 'Songs, artists, albums, folders…', value: lastQuery, style: { fontSize: '16px', padding: '14px 18px' } });
  const res = el('div');
  const web = el('div.row.wrap', { style: { margin: '14px 0' } },
    el('button.btn', { onclick: () => api.openExternal('https://www.google.com/search?q=' + encodeURIComponent(input.value || 'music')) }, 'Search Google'),
    el('button.btn', { onclick: () => openYouTube(input.value.trim() || 'songs') }, 'Search YouTube'),
    el('button.btn', { onclick: () => importYouTube() }, 'Import a YouTube playlist'));
  main.append(input, web, res);
  function run() {
    lastQuery = input.value;
    res.innerHTML = '';
    const q = input.value.trim().toLowerCase();
    if (!q) { res.appendChild(el('div.empty', 'Type to search your music and folders.')); return; }
    const folders = L.playlists.filter((p) => !isHidden(p) && p.name.toLowerCase().includes(q)).slice(0, 12);
    if (folders.length) { res.appendChild(el('h2', 'Folders')); res.appendChild(foldersBlock(folders, null, false)); }
    const songs = searchSongs(q);
    res.appendChild(el('h2', 'Songs'));
    res.appendChild(songsBlock(songs, { empty: `Nothing in your library for "${input.value.trim()}". Try Google or YouTube above.` }));
    markPlaying();
  }
  input.addEventListener('input', run);
  run();
  setTimeout(() => input.focus(), 30);
}

// ---------- YouTube ----------
let ytApi = null;
function loadYtApi() {
  if (ytApi) return ytApi;
  ytApi = new Promise((resolve) => {
    window.onYouTubeIframeAPIReady = () => resolve(window.YT);
    document.head.appendChild(el('script', { src: 'https://www.youtube.com/iframe_api' }));
    setTimeout(() => { if (window.YT && window.YT.Player) resolve(window.YT); }, 8000);
  });
  return ytApi;
}

async function importYouTube(prefill) {
  const link = await prompt('Import YouTube playlist', prefill || '', 'https://www.youtube.com/playlist?list=…',
    'Paste a YouTube playlist link. It must be Public or Unlisted. The videos play in YouTube\'s own player inside Echo.');
  if (link == null) return;
  const m = /[?&]list=([A-Za-z0-9_-]+)/.exec(link);
  const id = m ? m[1] : (/^[A-Za-z0-9_-]{12,}$/.test(link.trim()) ? link.trim() : null);
  if (!id) { alertBox('Not a playlist link', 'That doesn\'t look like a playlist link. It should contain "list=".'); return; }
  const name = (await prompt('Playlist name', 'YouTube playlist')) || 'YouTube playlist';
  const prog = progressBox('Importing from YouTube');
  prog.set(5, 'Opening the playlist…');
  try {
    const YT = await loadYtApi();
    if (!YT || !YT.Player) throw new Error('YouTube didn\'t load. Check your internet.');
    const holder = el('div', { style: { position: 'fixed', left: '-2000px', width: '320px', height: '180px' } }, el('div#ytprobe'));
    document.body.appendChild(holder);
    const ids = await new Promise((resolve, reject) => {
      let tries = 0;
      const pl = new YT.Player('ytprobe', {
        width: 320, height: 180, playerVars: { listType: 'playlist', list: id },
        events: {
          onReady: () => {
            try { pl.cuePlaylist({ listType: 'playlist', list: id }); } catch (e) { /* ignore */ }
            const t = setInterval(() => {
              tries++;
              let l = null;
              try { l = pl.getPlaylist(); } catch (e) { /* not yet */ }
              if (l && l.length) { clearInterval(t); resolve(l); }
              else if (tries > 50) { clearInterval(t); reject(new Error('Couldn\'t read this playlist. Check that it\'s Public or Unlisted.')); }
            }, 400);
          },
          onError: (e) => reject(new Error('YouTube error ' + e.data + '. Check that the playlist is Public or Unlisted.')),
        },
      });
    });
    holder.remove();
    prog.set(20, `Found ${ids.length} videos. Getting their names…`);
    const off = api.onYtProgress; // progress events
    let done = 0;
    off((i, n) => { done = i; prog.set(20 + Math.round(i / n * 75), `Getting names… ${i} of ${n}`); });
    const items = (await api.oembed(ids)).filter(Boolean);
    const p = { id: newId(), name, youtube: true, ytListId: id, parentId: null, songs: [], yt: items };
    L.playlists.push(p);
    save();
    prog.close();
    if (items.length < ids.length) toast(`${ids.length - items.length} private or deleted videos were skipped.`);
    openFolder(p.id);
  } catch (e) {
    prog.close();
    alertBox('Not imported', e.message);
  }
}

let ytPlayer = null;
function youtubeView(p) {
  if (!p) { go('library'); return; }
  main.appendChild(el('div.crumbs', el('a', { onclick: () => go('library') }, 'Library')));
  main.appendChild(head(p.name, iconBtn('more', 'More', (e) => folderMenu(e, p))));
  const holder = el('div', { style: { aspectRatio: '16 / 9', maxWidth: '900px', borderRadius: '16px', overflow: 'hidden', background: '#000' } }, el('div#ytplayer'));
  main.appendChild(holder);
  main.appendChild(el('div.row.wrap', { style: { margin: '14px 0' } },
    el('button.btn.primary', { onclick: () => matchYoutube(p) }, 'Find these in my songs'),
    el('button.btn', { onclick: () => openYouTube('https://www.youtube.com/playlist?list=' + p.ytListId) }, 'Open in YouTube'),
    el('span.sub', subtitleOf(p))));
  const list = el('div.list');
  p.yt.forEach((it, i) => list.appendChild(el('div.item', { style: { gridTemplateColumns: '36px 1fr' }, 'data-yt': i,
    onclick: () => { if (ytPlayer && ytPlayer.loadPlaylist) ytPlayer.loadPlaylist(p.yt.map((x) => x.v), i); markRecent(p.id); } },
  el('div.num', String(i + 1)), el('div', el('div.title', it.t), el('div.s', it.a)))));
  main.appendChild(list);
  loadYtApi().then((YT) => {
    if (!YT || NAV.view !== 'youtube' || !$('#ytplayer')) return;
    ytPlayer = new YT.Player('ytplayer', {
      width: '100%', height: '100%', playerVars: { rel: 0, playsinline: 1 },
      events: {
        onReady: () => ytPlayer.cuePlaylist(p.yt.map((x) => x.v), 0),
        onStateChange: (e) => {
          if (e.data === 1) audio.pause(); // stop Echo's music while YouTube plays
          let i = -1;
          try { i = ytPlayer.getPlaylistIndex(); } catch (err) { /* ignore */ }
          $$('[data-yt]').forEach((n) => n.classList.toggle('playing', +n.dataset.yt === i));
        },
      },
    });
  });
}

function normTitle(s) {
  return String(s || '').toLowerCase().replace(/\(.*?\)|\[.*?\]|\{.*?\}/g, ' ').replace(/[^\p{L}\p{N}]+/gu, ' ')
    .replace(/\b(official|music|video|audio|lyrics?|lyrical|hd|hq|4k|full|song|ft|feat|featuring|mv|visualizer|remastered|version|topic|vevo|new|latest)\b/g, ' ')
    .trim().replace(/ +/g, ' ');
}
async function matchYoutube(p) {
  const songs = sortedSongs(), found = [];
  for (const it of p.yt) {
    const yt = new Set(normTitle(it.t + ' ' + it.a).split(' '));
    let best = null, bestScore = 0;
    for (const s of songs) {
      const t = normTitle(s.title).split(' ').filter(Boolean);
      if (!t.length) continue;
      const hit = t.filter((w) => yt.has(w)).length;
      let score = hit / t.length;
      if (t.length === 1 && (score < 1 || t[0].length < 4)) continue;
      const ar = normTitle(s.artist).split(' ').filter(Boolean);
      if (ar.length) score += 0.25 * ar.filter((w) => yt.has(w)).length / ar.length;
      if (score > bestScore) { bestScore = score; best = s; }
    }
    if (best && bestScore >= 0.8 && !found.includes(best)) found.push(best);
  }
  if (!found.length) { alertBox('No matches', 'None of these videos matched songs in your library. Import your music first (a PC folder or Google Drive), then try again.'); return; }
  if (!(await confirmBox(`Found ${found.length} of ${p.yt.length}`, `Make a folder "${p.name} (my songs)" with the ${found.length} songs found? It plays without YouTube.`, 'Make folder'))) return;
  const np = newPlaylist(p.name + ' (my songs)', null);
  np.songs.push(...found.map((s) => s.id));
  save();
  openFolder(np.id);
}

// ---------- sidebar folders ----------
function renderSideFolders() {
  const box = $('#sideFolders');
  box.innerHTML = '';
  for (const p of visibleChildren(null).slice(0, 30)) {
    const n = el('div.nav' + (NAV.view === 'folder' && NAV.arg === p.id ? '.on' : ''), { onclick: () => openFolder(p.id), oncontextmenu: (e) => folderMenu(e, p), title: p.name },
      el('span.dot', { style: { background: p.youtube ? YT_GRAD : grad(p.name) } }), el('span', { style: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, p.name));
    dragFolder(n, p);
    box.appendChild(n);
  }
}
