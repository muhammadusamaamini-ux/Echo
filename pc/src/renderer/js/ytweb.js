// YouTube inside Echo (like the phone): browse and search YouTube in Echo itself, and a Download button
// on every video saves it as audio (joins your songs, and syncs to your phone) or as video (Videos › Echo).
'use strict';

const YTW = { url: 'https://www.youtube.com/', jobs: new Map(), seq: 0 };
const UA = navigator.userAgent.replace(/ (Electron|Echo|echo)\/\S+/g, '');

function ytVideoId(url) {
  const m = /(?:[?&]v=|youtu\.be\/|\/shorts\/|\/live\/|\/embed\/)([A-Za-z0-9_-]{11})/.exec(url || '');
  return m ? m[1] : null;
}

/** Opens YouTube in Echo. A search text or a YouTube link both work. */
function openYouTube(urlOrSearch) {
  if (urlOrSearch) {
    const t = String(urlOrSearch).trim();
    YTW.url = /^https?:\/\//.test(t) ? t : 'https://www.youtube.com/results?search_query=' + encodeURIComponent(t);
  }
  go('ytweb');
}

function ytWebView() {
  main.classList.add('webmode');
  const wv = document.createElement('webview');
  wv.setAttribute('partition', 'persist:youtube'); // stays signed in, like a browser
  wv.setAttribute('useragent', UA);
  wv.setAttribute('src', YTW.url);
  wv.className = 'ytweb';
  const addr = el('input.addr', { type: 'text', value: YTW.url, spellcheck: false, placeholder: 'Search YouTube or paste a link',
    onkeydown: (e) => { if (e.key === 'Enter') { const t = addr.value.trim(); if (t) wv.loadURL(/^https?:\/\//.test(t) ? t : 'https://www.youtube.com/results?search_query=' + encodeURIComponent(t)); } } });
  const dl = el('button.btn.primary.dlbtn', { onclick: () => ytDownload(wv.getURL(), wv.getTitle()) }, '⬇ Download');
  dl.style.display = ytVideoId(YTW.url) ? '' : 'none';
  const back = iconBtn('back', 'Back', () => wv.canGoBack() && wv.goBack());
  const fwd = iconBtn('back', 'Forward', () => wv.canGoForward() && wv.goForward());
  fwd.firstChild.style.transform = 'scaleX(-1)';
  const bar = el('div.webbar', back, fwd,
    iconBtn('update', 'Reload', () => wv.reload()),
    iconBtn('home', 'YouTube home', () => wv.loadURL('https://www.youtube.com/')),
    addr, dl);
  const update = () => {
    let u = '';
    try { u = wv.getURL(); } catch (e) { return; }
    YTW.url = u;
    if (document.activeElement !== addr) addr.value = u;
    dl.style.display = ytVideoId(u) ? '' : 'none';
  };
  wv.addEventListener('did-navigate', update);
  wv.addEventListener('did-navigate-in-page', update); // YouTube changes pages without reloading
  wv.addEventListener('page-title-updated', update);
  // music in Echo pauses while a YouTube video plays
  wv.addEventListener('media-started-playing', () => { if (!audio.paused) audio.pause(); });
  main.appendChild(el('div.webwrap', bar, wv));
}

// ---------- downloading ----------

async function ytDownload(url, pageTitle) {
  const id = ytVideoId(url);
  if (!id) { toast('Open a video first'); return; }
  const clean = 'https://www.youtube.com/watch?v=' + id;
  const wait = progressBox('Getting the video\'s details…');
  wait.set(30, (pageTitle || '').replace(/ - YouTube$/, '') || 'One moment');
  const info = await api.ytInfo(clean);
  wait.close();
  if (!info || info.error) { alertBox('Can\'t download', (info && info.error) || 'Something went wrong.'); return; }
  const opts = [{ label: '🎵 Audio (M4A) — joins your Echo songs', audio: true }];
  for (const h of info.heights.filter((x) => x <= 1080)) opts.push({ label: `🎬 Video ${h}p (MP4)`, height: h });
  if (opts.length === 1) opts.push({ label: '🎬 Video (MP4)', height: 720 });
  const i = await choose(info.title, opts.map((o) => o.label), 'Save as:');
  if (i < 0) return;
  startYtJob(clean, info, opts[i]);
}

function startYtJob(url, info, opt) {
  const id = 'y' + (++YTW.seq);
  const row = el('div.dlrow');
  const name = el('div.dlname', (opt.audio ? '🎵 ' : '🎬 ') + info.title);
  const stat = el('div.dlstat', 'Starting…');
  const bar = el('div.bar', el('i'));
  const x = iconBtn('close', 'Cancel', () => { api.ytCancel(id); row.remove(); tidyDl(); });
  row.append(el('div.dlhead', name, x), bar, stat);
  dlPanel().appendChild(row);
  YTW.jobs.set(id, { row, stat, bar });
  toast(opt.audio ? 'Downloading audio… It will appear in your songs.' : 'Downloading video… It will be in Videos › Echo.');
  api.ytDownload(id, url, { audio: !!opt.audio, height: opt.height }).then(async (r) => {
    YTW.jobs.delete(id);
    if (!row.isConnected) return; // cancelled
    if (!r || !r.ok) {
      r = r || {};
      stat.textContent = 'Failed';
      row.classList.add('err');
      if (!r.cancelled) alertBox('Download failed', r.error || 'Try again in a little while.');
      setTimeout(() => { row.remove(); tidyDl(); }, 8000);
      return;
    }
    bar.firstChild.style.width = '100%';
    if (opt.audio) {
      const s = { id: localSongId(r.path), source: 'file', path: r.path, uri: await api.fileUrl(r.path), title: info.title,
        artist: info.author, album: 'YouTube', duration: Math.round((info.duration || 0) * 1000), added: Date.now() };
      addSong(s);
      save();
      if (NAV.view === 'songs' || NAV.view === 'home') refreshView();
      stat.innerHTML = '';
      stat.append('Saved · in your songs ', el('a', { href: '#', onclick: (e) => { e.preventDefault(); playList([song(s.id) || s], 0); } }, 'Play'));
    } else {
      VIDS = null; // the Videos page shows it next time
      const v = { path: r.path, url: await api.fileUrl(r.path), name: r.path.split(/[\\/]/).pop() };
      stat.innerHTML = '';
      stat.append('Saved to Videos › Echo ', el('a', { href: '#', onclick: (e) => { e.preventDefault(); openVideo([v], 0); } }, 'Watch'),
        ' · ', el('a', { href: '#', onclick: (e) => { e.preventDefault(); api.showInFolder(r.path); } }, 'Show in folder'));
    }
    setTimeout(() => { row.remove(); tidyDl(); }, 30000);
  });
}

function dlPanel() {
  let p = $('#dlPanel');
  if (!p) { p = el('div#dlPanel'); document.body.appendChild(p); }
  return p;
}
function tidyDl() { const p = $('#dlPanel'); if (p && !p.children.length) p.remove(); }

api.onYtdlProgress((id, p) => {
  const j = YTW.jobs.get(id);
  if (!j) return;
  j.bar.firstChild.style.width = Math.min(100, p.pct || 0) + '%';
  j.stat.textContent = p.step || (Math.round(p.pct || 0) + '%' + (p.size ? ' of ' + p.size : ''));
});
