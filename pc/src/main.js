// Echo for Windows — main process: window, files, Google Drive, FFmpeg and updates.
const { app, BrowserWindow, ipcMain, dialog, shell, session, Notification, nativeImage, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { pathToFileURL } = require('url');
const drive = require('./lib/drive');
const scan = require('./lib/scan');
const ff = require('./lib/ffmpeg');
const ytdl = require('./lib/ytdl');
const stream = require('./lib/stream');
stream.registerScheme(); // plays MKV, AVI, HEVC… through FFmpeg (must be before the app is ready)

let win = null;
let reallyQuit = false; // the window's ✕ asks first (tab, music or Echo); this skips the question
let pendingFiles = [];
const userData = () => app.getPath('userData');
const libFile = () => path.join(userData(), 'library.json');
const thumbDir = () => { const d = path.join(userData(), 'thumbs'); fs.mkdirSync(d, { recursive: true }); return d; };
const tmpDir = () => { const d = path.join(os.tmpdir(), 'echo-pc'); fs.mkdirSync(d, { recursive: true }); return d; };

function mediaArgs(argv) {
  return argv.slice(1).filter((a) => {
    if (!a || a.startsWith('-')) return false;
    try { return fs.statSync(a).isFile() && (scan.isAudio(a) || scan.isVideo(a)); } catch (e) { return false; }
  });
}

// Only one Echo at a time: opening a file while Echo runs hands it to the open window.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (e, argv) => {
    const files = mediaArgs(argv);
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
      if (files.length) win.webContents.send('open-files', files.map(fileInfo));
    }
  });
}

function fileInfo(p) {
  return { path: p, url: pathToFileURL(p).href, name: path.basename(p, path.extname(p)), audio: scan.isAudio(p) };
}

function createWindow() {
  win = new BrowserWindow({
    width: 1320, height: 860, minWidth: 940, minHeight: 600,
    backgroundColor: '#0b0b1e', title: 'Echo', autoHideMenuBar: true,
    icon: path.join(__dirname, 'renderer', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false,
      webviewTag: true, autoplayPolicy: 'no-user-gesture-required' },
  });
  Menu.setApplicationMenu(null);
  // YouTube inside Echo: links that open a new window open in the same page instead
  win.webContents.on('did-attach-webview', (ev, wc) => {
    wc.setWindowOpenHandler(({ url }) => {
      if (/^https?:\/\/([\w-]+\.)*(youtube\.com|youtu\.be)\//.test(url)) win.webContents.send('web:newtab', url); // a new Echo tab
      else if (/^https?:\/\/([\w-]+\.)*google\.com\//.test(url)) wc.loadURL(url); // signing in
      else if (/^https?:/.test(url)) shell.openExternal(url);
      return { action: 'deny' };
    });
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.on('close', (e) => {
    if (reallyQuit || win.webContents.isCrashed()) return;
    e.preventDefault();
    win.webContents.send('app:closeAsk');
  });
  win.on('enter-full-screen', () => win.webContents.send('fullscreen', true));
  win.on('leave-full-screen', () => win.webContents.send('fullscreen', false));
}

app.whenReady().then(() => {
  stream.handle();
  // YouTube's embedded player refuses to play without a web address it recognises.
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['*://*.youtube.com/*', '*://*.youtube-nocookie.com/*', '*://*.googlevideo.com/*'] },
    (d, cb) => {
      if (!d.requestHeaders.Referer || d.requestHeaders.Referer.startsWith('file:')) d.requestHeaders.Referer = 'https://www.youtube.com/';
      cb({ requestHeaders: d.requestHeaders });
    });
  pendingFiles = mediaArgs(process.argv).map(fileInfo);
  createWindow();
  setupUpdates();
});

app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { reallyQuit = true; });
ipcMain.handle('app:quit', () => { reallyQuit = true; app.quit(); });
ipcMain.handle('app:minimize', () => { if (win) win.minimize(); });

// ---------- library ----------
ipcMain.handle('lib:load', () => {
  try { return JSON.parse(fs.readFileSync(libFile(), 'utf8')); } catch (e) { return null; }
});
ipcMain.handle('lib:save', (e, data) => {
  const tmp = libFile() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data));
  fs.renameSync(tmp, libFile());
  return true;
});
ipcMain.handle('app:pendingFiles', () => { const f = pendingFiles; pendingFiles = []; return f; });
ipcMain.handle('app:info', () => ({ version: app.getVersion(), videos: app.getPath('videos'), music: app.getPath('music'),
  downloads: app.getPath('downloads'), ffmpeg: ff.bin() }));

// ---------- files ----------
ipcMain.handle('dialog:open', async (e, opts) => {
  const r = await dialog.showOpenDialog(win, opts);
  if (r.canceled) return [];
  return r.filePaths.map((p) => {
    let isDir = false;
    try { isDir = fs.statSync(p).isDirectory(); } catch (err) { /* ignore */ }
    return isDir ? { path: p, name: path.basename(p), dir: true } : fileInfo(p);
  });
});
ipcMain.handle('dialog:save', async (e, opts) => {
  const r = await dialog.showSaveDialog(win, opts);
  return r.canceled ? null : r.filePath;
});
ipcMain.handle('file:url', (e, p) => pathToFileURL(p).href);
ipcMain.handle('file:mtimes', (e, paths) => paths.map((p) => { try { return fs.statSync(p).mtimeMs; } catch (err) { return 0; } }));
ipcMain.handle('file:exists', (e, p) => { try { return fs.statSync(p).isFile(); } catch (err) { return false; } });
ipcMain.handle('file:isDir', (e, p) => { try { return fs.statSync(p).isDirectory(); } catch (err) { return false; } });
ipcMain.handle('file:writeTemp', (e, name, dataUrl) => {
  const out = path.join(tmpDir(), name);
  fs.writeFileSync(out, Buffer.from(dataUrl.split(',')[1], 'base64'));
  return out;
});
ipcMain.handle('file:writeData', (e, p, dataUrl) => { fs.writeFileSync(p, Buffer.from(dataUrl.split(',')[1], 'base64')); return p; });
ipcMain.handle('file:readText', (e, p) => fs.readFileSync(p, 'utf8'));
ipcMain.handle('file:delete', (e, p) => { try { fs.unlinkSync(p); return true; } catch (err) { return false; } });
ipcMain.handle('shell:show', (e, p) => shell.showItemInFolder(p));
ipcMain.handle('shell:open', (e, url) => shell.openExternal(url));
ipcMain.handle('shell:openPath', (e, p) => shell.openPath(p));
ipcMain.handle('win:fullscreen', (e, on) => { win.setFullScreen(on == null ? !win.isFullScreen() : !!on); return win.isFullScreen(); });
ipcMain.handle('win:progress', (e, v) => { win.setProgressBar(v); });

ipcMain.handle('scan:music', (e, dir) => scan.musicTree(dir));
ipcMain.handle('scan:videos', async (e, dirs) => (await scan.videos(dirs)).map((v) => ({ ...v, url: pathToFileURL(v.path).href })));

// Song details (title, artist, album, length) from the file's tags
let mm = null;
async function tags(p) {
  if (!mm) mm = require('music-metadata');
  try {
    const m = await mm.parseFile(p, { duration: true, skipCovers: true });
    return { title: m.common.title || '', artist: m.common.artist || '', album: m.common.album || '',
      duration: Math.round((m.format.duration || 0) * 1000) };
  } catch (e) {
    return { title: '', artist: '', album: '', duration: 0 };
  }
}
ipcMain.handle('meta:tags', async (e, paths) => {
  const out = [];
  for (const p of paths) out.push(await tags(p));
  return out;
});
const artCache = new Map();
ipcMain.handle('meta:art', async (e, p) => {
  if (artCache.has(p)) return artCache.get(p);
  let url = null;
  try {
    if (!mm) mm = require('music-metadata');
    const m = await mm.parseFile(p, { skipPostHeaders: true });
    const pic = m.common.picture && m.common.picture[0];
    if (pic) url = `data:${pic.format};base64,${Buffer.from(pic.data).toString('base64')}`;
  } catch (err) { /* no artwork */ }
  if (artCache.size > 200) artCache.clear();
  artCache.set(p, url);
  return url;
});

// ---------- Google Drive & YouTube ----------
ipcMain.handle('drive:read', (e, id) => drive.read(id));
ipcMain.handle('yt:oembed', async (e, ids) => {
  const out = new Array(ids.length).fill(null);
  let next = 0;
  async function worker() {
    while (next < ids.length) {
      const i = next++;
      try {
        const r = await fetch('https://www.youtube.com/oembed?format=json&url=' +
          encodeURIComponent('https://www.youtube.com/watch?v=' + ids[i]));
        if (r.ok) {
          const j = await r.json();
          out[i] = { v: ids[i], t: j.title || '', a: (j.author_name || '').replace(' - Topic', '').replace(/VEVO$/, '').trim() };
        }
      } catch (err) { /* skip */ }
      if (win) win.webContents.send('yt:progress', i + 1, ids.length);
    }
  }
  await Promise.all([worker(), worker(), worker(), worker()]);
  return out;
});

// ---------- FFmpeg ----------
// YouTube downloads (the in-app YouTube page's Download button)
ipcMain.handle('ytdl:info', (e, url) => ytdl.info(url));
ipcMain.handle('ytdl:download', (e, id, url, choice) => ytdl.download(id, url, choice, (p) => { if (win && !win.isDestroyed()) win.webContents.send('ytdl:progress', id, p); }));
ipcMain.handle('ytdl:cancel', (e, id) => ytdl.cancel(id));
ipcMain.handle('ff:probe', (e, p) => ff.probe(p));
// videos' lengths, for filtering and sorting by length (kept in a small file so it's quick next time)
let durCache = null;
ipcMain.handle('video:durations', async (e, items) => {
  const file = path.join(userData(), 'video-lengths.json');
  if (!durCache) { try { durCache = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (err) { durCache = {}; } }
  const out = new Array(items.length);
  const todo = [];
  items.forEach((it, i) => { const c = durCache[it.path]; if (c && c.m === it.mtime) out[i] = c.d; else todo.push(i); });
  let k = 0;
  const worker = async () => {
    while (k < todo.length) {
      const i = todo[k++];
      let d = 0;
      try { d = (await ff.probe(items[i].path)).duration || 0; } catch (err) { /* unreadable */ }
      out[i] = d;
      durCache[items[i].path] = { m: items[i].mtime, d };
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
  if (todo.length) { try { fs.writeFileSync(file, JSON.stringify(durCache)); } catch (err) { /* ignore */ } }
  return out;
});
ipcMain.handle('stream:info', (e, p) => stream.info(p));
ipcMain.handle('stream:stop', () => stream.stopAll());
ipcMain.handle('ff:thumb', async (e, p) => {
  const key = Buffer.from(p).toString('base64').replace(/[/+=]/g, '').slice(-60);
  const out = path.join(thumbDir(), key + '.jpg');
  const infoFile = out + '.json';
  try {
    const info = JSON.parse(fs.readFileSync(infoFile, 'utf8'));
    if (fs.existsSync(out)) return { ...info, thumb: pathToFileURL(out).href };
  } catch (err) { /* make it */ }
  const info = await ff.thumb(p, out);
  try { fs.writeFileSync(infoFile, JSON.stringify(info)); } catch (err) { /* ignore */ }
  return { ...info, thumb: info.thumb ? pathToFileURL(out).href + '?v=' + Date.now() : null };
});
ipcMain.handle('ff:waveform', (e, p) => ff.waveform(p));
ipcMain.handle('ff:frame', async (e, p, at, out) => { await ff.frame(p, at, out); return out; });
// the small picture above the progress bar when you hover it (for videos played through Echo's converter)
ipcMain.handle('ff:preview', async (e, p, at) => {
  const out = path.join(tmpDir(), 'preview-' + crypto.createHash('md5').update(p).digest('hex').slice(0, 10) + '-' + Math.round(at) + '.jpg');
  if (!fs.existsSync(out)) {
    try { await ff.run(['-hide_banner', '-y', '-ss', at.toFixed(2), '-i', p, '-frames:v', '1', '-vf', 'scale=240:-2', '-q:v', '6', out], 0); } catch (err) { return null; }
  }
  return fs.existsSync(out) ? pathToFileURL(out).href : null;
});
ipcMain.handle('ff:outSize', (e, project) => ff.outSize(project));
ipcMain.handle('ff:cancel', (e, id) => ff.cancel(id));
ipcMain.handle('ff:export', async (e, job) => {
  let built;
  if (job.kind === 'video') built = ff.videoArgs(job.project, job.out, false);
  else if (job.kind === 'extract') built = ff.videoArgs(job.project, job.out, true);
  else if (job.kind === 'audio') built = ff.audioArgs(job.project, job.out, job.format);
  else if (job.kind === 'convert') built = { args: ff.convertArgs(job.file, job.out), total: job.total || 0 };
  else throw new Error('Unknown job');
  try {
    await ff.run(built.args, built.total, (pct) => {
      e.sender.send('ff:progress', job.id, pct);
      if (win) win.setProgressBar(pct / 100);
    }, job.id);
    return { ok: true, out: job.out, url: pathToFileURL(job.out).href };
  } catch (err) {
    return { ok: false, cancelled: !!err.cancelled, error: err.message };
  } finally {
    if (win) win.setProgressBar(-1);
  }
});

// ---------- phone ⇄ PC sync (files go to your private GitHub data repository) ----------
ipcMain.handle('sync:hash', (e, p) => new Promise((resolve) => {
  const h = crypto.createHash('sha1');
  const s = fs.createReadStream(p);
  s.on('data', (d) => h.update(d));
  s.on('error', () => resolve(null));
  s.on('end', () => resolve(h.digest('hex')));
}));
ipcMain.handle('sync:size', (e, p) => { try { return fs.statSync(p).size; } catch (err) { return -1; } });
function gh(cfg, method, apiPath, body, accept) {
  return fetch('https://api.github.com/repos/' + cfg.repo + apiPath, {
    method, cache: 'no-store',
    headers: { Authorization: 'Bearer ' + cfg.token, Accept: accept || 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
}
/** Uploads a music file once (files are named by their content, so the same song is never stored twice). */
ipcMain.handle('sync:upload', async (e, cfg, remotePath, localPath) => {
  const head = await gh(cfg, 'GET', '/contents/' + remotePath + '?ref=' + encodeURIComponent(cfg.branch || 'main'));
  if (head.ok) return { ok: true, existed: true };
  const data = fs.readFileSync(localPath).toString('base64');
  const r = await gh(cfg, 'PUT', '/contents/' + remotePath, { message: 'Echo sync: add music file', content: data, branch: cfg.branch || undefined });
  if (r.ok || r.status === 422) return { ok: true };
  return { ok: false, status: r.status, error: (await r.text()).slice(0, 300) };
});
/** Downloads a synced music file into Music\Echo (or wherever dest is). */
ipcMain.handle('sync:download', async (e, cfg, remotePath, dest) => {
  const r = await gh(cfg, 'GET', '/contents/' + remotePath + '?ref=' + encodeURIComponent(cfg.branch || 'main'), null, 'application/vnd.github.raw');
  if (!r.ok) return { ok: false, status: r.status };
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  let out = dest, n = 1;
  while (fs.existsSync(out)) out = dest.replace(/(\.[^.\\/]+)$/, ` (${n++})$1`);
  fs.writeFileSync(out, Buffer.from(await r.arrayBuffer()));
  return { ok: true, path: out, url: pathToFileURL(out).href };
});
ipcMain.handle('sync:musicDir', () => path.join(app.getPath('music'), 'Echo'));

// ---------- updates ----------
let updater = null;
let lastInfo = null;
function notesText(info) {
  let n = info && info.releaseNotes;
  if (Array.isArray(n)) n = n.map((x) => x.note || '').join('\n');
  return String(n || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim();
}
function setupUpdates() {
  try {
    updater = require('electron-updater').autoUpdater;
  } catch (e) {
    return;
  }
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = true;
  updater.on('update-available', (info) => {
    lastInfo = info;
    const notes = notesText(info);
    if (win) win.webContents.send('upd:available', { version: info.version, notes });
    if (Notification.isSupported()) {
      const n = new Notification({
        title: `Echo update available (${info.version})`,
        body: notes ? "What's new: " + notes.split('\n').filter(Boolean).slice(0, 3).join(' · ') : 'Click to install it.',
        icon: path.join(__dirname, 'renderer', 'icon.png'),
      });
      n.on('click', () => { if (win) { win.show(); win.focus(); win.webContents.send('upd:available', { version: info.version, notes, open: true }); } });
      n.show();
    }
  });
  updater.on('download-progress', (p) => { if (win) win.webContents.send('upd:progress', Math.round(p.percent)); });
  updater.on('update-downloaded', () => { if (win) win.webContents.send('upd:downloaded'); });
  updater.on('error', (err) => { if (win) win.webContents.send('upd:error', String(err && err.message || err)); });
  const check = () => updater.checkForUpdates().catch(() => {});
  setTimeout(check, 8000);
  setInterval(check, 6 * 60 * 60 * 1000);
}
ipcMain.handle('upd:check', async () => {
  if (!updater) return { error: 'Updates only work in the installed app.' };
  try {
    const r = await updater.checkForUpdates();
    const v = r && r.updateInfo && r.updateInfo.version;
    if (v && v !== app.getVersion() && r.isUpdateAvailable !== false) {
      lastInfo = r.updateInfo;
      return { available: true, version: v, notes: notesText(r.updateInfo) };
    }
    return { available: false, version: app.getVersion() };
  } catch (e) {
    return { error: 'Couldn\'t reach GitHub. Check your internet connection and try again.' };
  }
});
ipcMain.handle('upd:download', async () => { if (updater) await updater.downloadUpdate(); return true; });
ipcMain.handle('upd:install', () => { reallyQuit = true; if (updater) updater.quitAndInstall(false, true); });
