// Startup, navigation, keyboard shortcuts, drag & drop, updates and About.
'use strict';

const FEATURES = [
  ['🎵 Music player', ['Spotify-style player with playlists, shuffle, repeat and a queue', 'Keeps playing when Echo is minimised; Windows media keys and the volume pop-up work',
    'Remembers what you were playing when you open Echo again', 'Click the song in the bottom bar for the big Now Playing view with the queue', 'The ✕ in the bottom bar closes the player']],
  ['🏠 Home, Search, Library, Songs', ['Home: greeting, Recently played, Your folders, Recently added', 'Search your songs and folders, or search Google and YouTube',
    'Library: all folders, filtered by PC folders, Google Drive, YouTube or your own playlists', 'Songs: every song, sortable by title, artist, album, date or length',
    'List or tile view: the ☰ / ▦ button']],
  ['📁 Folders', ['Folders inside folders; drag a folder onto another to move it', 'Right-click (or ⋮) for Play all, Shuffle, Rename, Move, Hide, Sync now, Delete',
    'Hide folders; find them in Library → Hidden folders', 'Deleting a folder removes its sub-folders and songs (songs also in other folders stay)']],
  ['☑️ Selecting songs', ['Click to play; Ctrl+click or Shift+click to select several; Ctrl+A selects all', 'Drag songs onto a folder (also in the sidebar) to add them',
    'Play, queue, add/move to a folder, or remove the selected songs from Echo']],
  ['💻 Music on this PC', ['Import a whole music folder: its sub-folders become folders in Echo', 'Stays in sync: new, renamed and deleted files show up automatically', 'Or pick single songs']],
  ['☁️ Google Drive', ['Paste a folder link shared as "Anyone with the link"; songs play online', 'Sub-folders keep their structure', 'Syncs when Echo opens and every hour, or with Sync now']],
  ['🗂️ Tabs (like File Explorer)', ['Every tab is its own Echo window: whatever you open in it (Home, a folder, Videos, a player, YouTube) shows there with its name',
    '+ or Ctrl+T: new tab · right-click a tab: Duplicate, Close, Close other tabs · Ctrl+W or middle-click: close · Ctrl+Tab: next tab',
    'The ← at the left of the tabs goes back to the previous page (Alt+← and the mouse\'s back button too); closing a video returns to the same place in the list',
    'Ctrl+click or middle-click a YouTube link to open it in a new tab',
    'The window\'s ✕ asks what to close: this video, this tab, the other tabs, the music, minimize, or quit Echo']],
  ['▶️ YouTube', ['YouTube opens inside Echo (sidebar → YouTube, or Search → Search YouTube), and stays signed in',
    'Download on any video: save it as audio (M4A, joins your songs and syncs to your phone) or as video up to 1080p',
    'You choose the folder and name in a Save as window; Echo remembers the folder for next time',
    'Import a YouTube playlist; it plays in YouTube\'s own player inside Echo', 'Find these in my songs: makes a folder from matching songs you already have']],
  ['🎬 Video player (like VLC)', ['Videos: all the videos in your Videos and Downloads folders (add more folders), with thumbnails',
    'Space play/pause · ← → 5 s · ↑ ↓ volume · mouse wheel volume · F or double-click full screen · Esc close',
    'Ctrl + mouse wheel (or two-finger pinch) to zoom; drag to move when zoomed', 'Fit / Fill / Stretch, rotate, picture-in-picture, speed 0.25×–3×',
    'Subtitles (.srt, .vtt), audio tracks, repeat, sleep timer, snapshot, remembers where you stopped',
    'Play as audio, or "Keep playing when I close the video"', 'MKV, AVI, HEVC (H.265), 10-bit, WMV and FLV play directly: Echo converts them while they play (seeking works), nothing is copied', 'Hover the progress bar to preview that moment (like YouTube)',
    'Double-click any video or audio file (choose Echo with "Open with"), drag files onto Echo, or open a network stream']],
  ['✂️ Video editor', ['Join videos and photos; trim, split, duplicate, reorder, delete', 'Speed 0.25×–4×, clip volume, voice effects, photo duration',
    '10 filters, brightness / contrast / saturation, rotate, flip', 'Canvas: 9:16 (TikTok, Reels, Shorts), 16:9, 1:1, 4:5, 4:3, 3:4 — crop or black bars',
    'Text (Urdu, Pashto and Arabic too), a sticker or picture, background music with its own volume',
    'Export 480p, 720p, 1080p or original MP4; Extract audio as MP3; Save frame as a picture']],
  ['🎚️ Audio editor', ['Waveform: drag the edges to trim, click to jump', 'Split, cut out a section, join several songs (or the sound of videos)',
    'Speed, voice / pitch, volume up to 300%, fade in and out, mix with background music', 'Save as MP3, M4A or WAV — one file or each part separately; saved audio joins your songs']],
  ['🔁 Phone ⇄ PC sync', ['Sidebar → Sync: connect your private Echo-Data repository on GitHub (the same way as Book Shelf)',
    'Folders, playlists, hidden folders, Google Drive folders and YouTube playlists are the same on your phone and PC',
    'Songs from your phone or PC are uploaded once and downloaded on the other device (to Music › Echo), so they play offline',
    'Removing a song or folder on one device removes it on the other; syncs automatically after changes and every few minutes']],
  ['🔄 Updates', ['About & updates → Check for updates', 'Echo checks by itself and shows a Windows notification with what\'s new',
    'The phone and PC versions are updated together']],
];

async function about() {
  const info = await api.info();
  const body = el('div');
  body.appendChild(el('p', { html: `Echo for Windows, version <b>${esc(info.version)}</b>. Your personal music &amp; video app — the same Echo as on your phone.` }));
  for (const [t, items] of FEATURES) {
    body.appendChild(el('h4', { style: { color: 'var(--glow)', margin: '16px 0 6px' } }, t));
    body.appendChild(el('ul', { style: { margin: 0, paddingLeft: '20px' } }, items.map((x) => el('li', x))));
  }
  const v = await dialog({ title: 'Echo — what it can do', body, wide: true,
    buttons: [{ text: 'Close', value: false }, { text: 'Check for updates', primary: true, value: true }] });
  if (v) checkUpdates(true);
}

// ---------- updates ----------
let updateInfo = null;
async function checkUpdates(manual) {
  if (manual) toast('Checking for updates…');
  const r = await api.checkUpdate();
  if (r.error) { if (manual) alertBox('Update check', r.error); return; }
  if (!r.available) { if (manual) alertBox('You\'re up to date', `You have the newest version (${r.version}).`); return; }
  offerUpdate(r);
}
async function offerUpdate(r) {
  updateInfo = r;
  const notes = r.notes ? '<br><br><b>What\'s new:</b><br>' + esc(r.notes).replace(/\n/g, '<br>') : '';
  const ok = await dialog({ title: `Update available (${r.version})`, body: 'A new version of Echo is ready.' + notes + '<br><br>Your music, folders and settings are kept.',
    buttons: [{ text: 'Later', value: false }, { text: 'Download and install', primary: true, value: true }] });
  if (!ok) return;
  const prog = progressBox('Downloading the update');
  api.onUpdateProgress((p) => prog.set(p, p + '%'));
  api.onUpdateDownloaded(async () => {
    prog.close();
    if (await confirmBox('Update ready', 'Echo will close, install the update and open again.', 'Install now')) api.installUpdate();
  });
  api.onUpdateError((e) => { prog.close(); alertBox('Update failed', e); });
  api.downloadUpdate();
}
api.onUpdateAvailable((r) => {
  if (r.open) offerUpdate(r);
  else { updateInfo = r; const b = $('#aboutBtn'); b.lastChild.textContent = 'Update available!'; b.style.color = 'var(--glow)'; b.onclick = () => offerUpdate(r); }
});

// ---------- keyboard ----------
document.addEventListener('keydown', (e) => {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName);
  const v = $('#vid');
  if (v) { // video player keys
    if (typing) return;
    const k = e.key;
    if (k === ' ' || k === 'k') { e.preventDefault(); toggleVideo(); }
    else if (k === 'ArrowRight') { V.seekTo(V.time() + (e.shiftKey ? 30 : 5)); flash('+' + (e.shiftKey ? 30 : 5) + ' s'); }
    else if (k === 'ArrowLeft') { V.seekTo(V.time() - (e.shiftKey ? 30 : 5)); flash('−' + (e.shiftKey ? 30 : 5) + ' s'); }
    else if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); v.volume = Math.max(0, Math.min(1, v.volume + (k === 'ArrowUp' ? 0.05 : -0.05))); flash('Volume ' + Math.round(v.volume * 100) + '%'); }
    else if (k === 'f' || k === 'F') api.fullscreen();
    else if (k === 'm' || k === 'M') { v.muted = !v.muted; flash(v.muted ? 'Muted' : 'Sound on'); }
    else if (k === 'Escape') { // in full screen, Esc only leaves full screen (like YouTube and VLC)
      if (document.fullscreenElement || document.body.classList.contains('fullscreen')) { if (document.fullscreenElement) document.exitFullscreen(); api.fullscreen(false); }
      else closeVideo();
    }
    else if (k === 'n' || k === 'N') stepVideo(1);
    else if (k === 'p' || k === 'P') stepVideo(-1);
    else if (k === 'a' || k === 'A') nextFit();
    else if (k === ']') { v.playbackRate = Math.min(3, v.playbackRate + 0.25); flash(v.playbackRate + 'x'); }
    else if (k === '[') { v.playbackRate = Math.max(0.25, v.playbackRate - 0.25); flash(v.playbackRate + 'x'); }
    else if (k === '+' || k === '=') { V.zoom = Math.min(5, V.zoom * 1.1); applyZoom(); flash('Zoom ' + Math.round(V.zoom * 100) + '%'); }
    else if (k === '-') { V.zoom = Math.max(1, V.zoom / 1.1); applyZoom(); flash('Zoom ' + Math.round(V.zoom * 100) + '%'); }
    return;
  }
  if (typing) return;
  if (NAV.view === 'aeditor' && $('#aePlayer')) {
    if (e.key === ' ') { e.preventDefault(); const p = $('#aePlayer'); if (p.paused) p.play(); else p.pause(); return; }
    if (e.key === '[') { startHere(); return; }
    if (e.key === ']') { endHere(); return; }
    if (e.key === 's' || e.key === 'S') { splitHere(); return; }
  }
  if (e.key === ' ') { e.preventDefault(); togglePlay(); }
  else if (e.key === 'Escape') { if ($('#nowplaying')) $('#nowplaying').remove(); else clearSelection(); }
  else if ((e.ctrlKey || e.metaKey) && (e.key === 'a' || e.key === 'A') && SEL.list.length) { e.preventDefault(); SEL.list.forEach((s) => SEL.ids.add(s.id)); selectionChanged(); }
  else if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); go('search'); }
  else if (e.ctrlKey && e.key === 'ArrowRight') next(false);
  else if (e.ctrlKey && e.key === 'ArrowLeft') prev();
  else if (e.key === 'ArrowRight' && P.song) audio.currentTime += 5;
  else if (e.key === 'ArrowLeft' && P.song) audio.currentTime -= 5;
  else if ((e.key === 's' || e.key === 'S') && !e.ctrlKey) toggleShuffle();
  else if ((e.key === 'r' || e.key === 'R') && !e.ctrlKey) cycleRepeat();
});

// ---------- drag files onto Echo ----------
document.addEventListener('dragover', (e) => { if (e.dataTransfer.types.includes('Files')) e.preventDefault(); });
document.addEventListener('drop', async (e) => {
  if (!e.dataTransfer.files.length) return;
  e.preventDefault();
  const files = [];
  for (const f of e.dataTransfer.files) {
    const p = api.pathForFile(f); // the file's real place on the PC
    if (!p) continue;
    const url = await api.fileUrl(p);
    files.push({ path: p, url, name: f.name.replace(/\.[^.]+$/, ''), audio: /\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|wma|amr|mka|weba)$/i.test(p) });
  }
  if (!files.length) return;
  if (NAV.view === 'veditor') addClips(files.map((f) => f.path));
  else if (NAV.view === 'aeditor') addAudio(files);
  else openFiles(files);
});

// ---------- start ----------
(async function start() {
  await loadLibrary();
  audio.volume = L.prefs.volume == null ? 0.8 : L.prefs.volume;
  syncVolumeUi();
  updateButtons();
  $$('#side .nav[data-go]').forEach((n) => n.addEventListener('click', () => go(n.dataset.go)));
  $('#importBtn').onclick = showImport;
  $('#aboutBtn').onclick = about;
  $('#syncBtn').onclick = openSyncSettings;
  if (syncCfg()) { setSyncState('idle', L.prefs.syncLast ? 'Last synced ' + new Date(L.prefs.syncLast).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Sync'); scheduleSync(2500); }
  setInterval(() => { if (syncCfg()) syncNow(false); }, 3 * 60 * 1000);
  renderSideFolders();
  go('home');
  restoreState();
  const pending = await api.pendingFiles();
  if (pending.length) openFiles(pending);
  api.onOpenFiles(openFiles);
  api.onFullscreen((on) => document.body.classList.toggle('fullscreen', on));
  const runSync = async () => {
    const r = await syncAll(true);
    if (r && (r.newSongs || r.removedSongs || r.newFolders || r.removedFolders)) { toast('Library updated: ' + summary(r, '')); refreshView(); }
  };
  setTimeout(runSync, 4000);
  setInterval(runSync, 60 * 60 * 1000);
})();
