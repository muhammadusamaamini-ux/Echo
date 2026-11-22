// Videos on this PC, and the video player (like VLC).
'use strict';

let VIDS = null; // scanned videos
let vidScanning = null;

async function videoDirs() {
  const info = await api.info();
  const dirs = L.videoDirs.length ? L.videoDirs : [info.videos, info.downloads];
  return dirs;
}
async function scanVideos(force) {
  if (VIDS && !force) return VIDS;
  if (!vidScanning) vidScanning = api.scanVideos(await videoDirs()).then((v) => { VIDS = v; vidScanning = null; return v; });
  return vidScanning;
}

function videosView(arg) {
  SEL.folder = null;
  const folderPath = arg && arg.folder;
  if (folderPath) {
    main.appendChild(el('div.crumbs', el('a', { onclick: () => go('videos') }, 'Videos')));
  }
  const filterBtn = el('button.btn.sortbtn' + (vfActive() ? '.on' : ''), { title: 'Show only some videos (length, date, size, name)', onclick: openVideoFilter },
    icon('filter'), vfActive() ? ' Filter on' : ' Filter');
  main.appendChild(head(folderPath ? folderPath.split(/[\\/]/).pop() : 'Videos',
    folderPath ? sortButton('v:' + folderPath, VIDEO_SORTS, 'name')
      : vfActive() ? sortButton('v:*', VIDEO_SORTS, 'modified') : sortButton('vfolders', VFOLDER_SORTS, 'name'),
    filterBtn, viewToggle(),
    folderPath ? null : el('button.btn', { onclick: addVideoFolder }, '+ Video folder'),
    el('button.btn', { onclick: openVideoFile }, 'Open file'),
    el('button.btn', { onclick: openStream }, 'Network stream'),
    el('button.btn.primary', { onclick: () => go('veditor') }, '✂ Edit a video')));
  if (vfActive()) {
    main.appendChild(el('div.filterbar', icon('filter'), el('span', 'Showing only: ' + vfSummary()),
      el('a', { onclick: openVideoFilter }, 'Change'), el('a', { onclick: () => { VF = vfEmpty(); refreshView(); } }, 'Clear filter')));
  }
  const box = el('div', el('div.empty', 'Looking for videos…'));
  main.appendChild(box);
  scanVideos(!folderPath && !VIDS).then(async (vids) => {
    if (NAV.view !== 'videos') return;
    const sortKey = folderPath ? 'v:' + folderPath : 'v:*';
    const mode = getSort(sortKey, folderPath ? 'name' : 'modified');
    if ((vfActive() && VF.len) || mode === 'longest' || mode === 'shortest') { // lengths are needed: read them (kept for next time)
      box.innerHTML = '';
      box.appendChild(el('div.empty', 'Reading the videos\' lengths…'));
      await fillVideoDurations(folderPath ? vids.filter((v) => v.folderPath === folderPath) : vids);
      if (NAV.view !== 'videos') return;
    }
    box.innerHTML = '';
    if (!folderPath && !vfActive()) {
      const groups = new Map();
      for (const v of vids) { if (!groups.has(v.folderPath)) groups.set(v.folderPath, []); groups.get(v.folderPath).push(v); }
      box.appendChild(el('div.sub', { style: { marginBottom: '14px' } },
        `${plural(vids.length, 'video')} in ${plural(groups.size, 'folder')} · looking in: `,
        ...L.videoDirs.length ? L.videoDirs.map((d, i) => el('span', i ? ', ' : '', el('a', { style: { color: 'var(--glow)', cursor: 'pointer' }, title: 'Right-click to stop looking here', oncontextmenu: (e) => menu(e, [['Stop looking in this folder', () => { L.videoDirs.splice(i, 1); save(); VIDS = null; refreshView(); }]]) }, d))) : ['your Videos and Downloads folders']));
      if (!vids.length) { box.appendChild(el('div.empty', 'No videos found. Use "+ Video folder" to choose where your videos are, or "Open file".')); return; }
      const list = sortVideoFolders([...groups.entries()], getSort('vfolders', 'name'));
      const wrap = L.prefs.grid ? el('div.cards.video') : el('div.list');
      for (const [fp, vs] of list) {
        const size = vs.reduce((t, v) => t + v.size, 0);
        if (L.prefs.grid) {
          const art = el('div.art', { style: { background: grad(fp) } }, icon('folder'));
          wrap.appendChild(el('div.card', { onclick: () => go('videos', { folder: fp }) },
            el('div.art-wrap', art), el('div.name', fp.split(/[\\/]/).pop()), el('div.meta', plural(vs.length, 'video') + ' · ' + fmtSize(size) + ' · ' + fp)));
          lazyThumb(art, vs[0], true);
        } else {
          const art = el('div.ico', { style: { width: '96px', height: '54px', background: grad(fp) } }, icon('folder'));
          wrap.appendChild(el('div.item', { style: { gridTemplateColumns: '100px 1fr 110px 90px' }, onclick: () => go('videos', { folder: fp }) },
            art, el('div', el('div.title', fp.split(/[\\/]/).pop()), el('div.s', fp)), el('div.dur', plural(vs.length, 'video')), el('div.dur', fmtSize(size))));
          lazyThumb(art, vs[0], true);
        }
      }
      box.appendChild(wrap);
      return;
    }
    // one folder, or (with a filter) the matching videos from every folder
    let vs = folderPath ? vids.filter((v) => v.folderPath === folderPath) : vids;
    if (vfActive()) vs = vs.filter(vfMatch);
    vs = sortVideos(vs, mode);
    if (vfActive()) box.appendChild(el('div.sub', { style: { marginBottom: '12px' } }, plural(vs.length, 'video') + ' match' + (vs.length === 1 ? 'es' : '') + (folderPath ? '' : ' in all your video folders')));
    if (!vs.length) { box.appendChild(el('div.empty', vfActive() ? 'No videos match this filter.' : 'No videos here.')); return; }
    box.appendChild(videoListBlock(vs, !folderPath));
  });
}

function videoListBlock(vs, showFolder) {
  const when = (v) => new Date(v.mtime).toLocaleDateString();
  if (L.prefs.grid) {
    const wrap = el('div.cards.video');
    vs.forEach((v, i) => {
      const art = el('div.art', { style: { background: grad(v.name) } }, icon('movie'));
      const badge = el('div.badge', v.duration ? fmtTime(v.duration * 1000) : '');
      wrap.appendChild(el('div.card', { onclick: () => openVideo(vs, i), oncontextmenu: (e) => videoMenu(e, vs, i) },
        el('div.art-wrap', art, badge), el('div.name', v.name), el('div.meta', fmtSize(v.size) + ' · ' + when(v) + (showFolder ? ' · ' + v.folder : ''))));
      lazyThumb(art, v, false, badge);
    });
    return wrap;
  }
  const list = el('div.list');
  vs.forEach((v, i) => {
    const art = el('div.ico', { style: { width: '96px', height: '54px', background: grad(v.name) } }, icon('movie'));
    const dur = el('div.dur', v.duration ? fmtTime(v.duration * 1000) : '');
    list.appendChild(el('div.item', { style: { gridTemplateColumns: '36px 100px 1fr 90px 70px 40px' }, onclick: () => openVideo(vs, i), oncontextmenu: (e) => videoMenu(e, vs, i) },
      el('div.num', String(i + 1)), art, el('div', el('div.title', v.name), el('div.s', new Date(v.mtime).toLocaleString() + (showFolder ? ' · ' + v.folderPath : ''))),
      el('div.dur', fmtSize(v.size)), dur, el('button.icon-btn', { onclick: (e) => videoMenu(e, vs, i) }, icon('more'))));
    lazyThumb(art, v, false, dur);
  });
  return list;
}

async function fillVideoDurations(vs) {
  const need = vs.filter((v) => v.duration == null);
  if (!need.length) return;
  const d = await api.videoDurations(need.map((v) => ({ path: v.path, mtime: v.mtime })));
  need.forEach((v, i) => { v.duration = d[i] || 0; });
}

// ---------- filter (length, date, size, name) ----------
const vfEmpty = () => ({ len: '', lenMin: '', lenMax: '', date: '', from: '', to: '', size: '', text: '' });
let VF = vfEmpty();
const vfActive = () => !!(VF.len || VF.date || VF.size || VF.text.trim());
const VF_LEN = [['', 'Any length'], ['u5', 'Under 5 minutes'], ['5-20', '5 – 20 minutes'], ['20-60', '20 – 60 minutes'], ['o60', 'Over 1 hour'], ['custom', 'Between…']];
const VF_DATE = [['', 'Any date'], ['today', 'Today'], ['7', 'Last 7 days'], ['30', 'Last 30 days'], ['year', 'This year'], ['custom', 'Between dates…']];
const VF_SIZE = [['', 'Any size'], ['u100', 'Under 100 MB'], ['100-1000', '100 MB – 1 GB'], ['o1000', 'Over 1 GB']];

function vfMatch(v) {
  const m = (v.duration || 0) / 60;
  if (VF.len === 'u5' && !(m < 5)) return false;
  if (VF.len === '5-20' && !(m >= 5 && m < 20)) return false;
  if (VF.len === '20-60' && !(m >= 20 && m < 60)) return false;
  if (VF.len === 'o60' && !(m >= 60)) return false;
  if (VF.len === 'custom') { if (VF.lenMin !== '' && m < +VF.lenMin) return false; if (VF.lenMax !== '' && m > +VF.lenMax) return false; }
  const now = new Date();
  const day0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (VF.date === 'today' && v.mtime < day0) return false;
  if (VF.date === '7' && v.mtime < Date.now() - 7 * 864e5) return false;
  if (VF.date === '30' && v.mtime < Date.now() - 30 * 864e5) return false;
  if (VF.date === 'year' && v.mtime < new Date(now.getFullYear(), 0, 1).getTime()) return false;
  if (VF.date === 'custom') {
    if (VF.from && v.mtime < new Date(VF.from + 'T00:00:00').getTime()) return false;
    if (VF.to && v.mtime > new Date(VF.to + 'T23:59:59').getTime()) return false;
  }
  const mb = v.size / 1048576;
  if (VF.size === 'u100' && !(mb < 100)) return false;
  if (VF.size === '100-1000' && !(mb >= 100 && mb < 1024)) return false;
  if (VF.size === 'o1000' && !(mb >= 1024)) return false;
  if (VF.text.trim() && !v.name.toLowerCase().includes(VF.text.trim().toLowerCase())) return false;
  return true;
}

function vfSummary() {
  const parts = [];
  const lab = (list, k) => (list.find((x) => x[0] === k) || ['', ''])[1];
  if (VF.len === 'custom') parts.push(`${VF.lenMin || 0}–${VF.lenMax || '∞'} min`); else if (VF.len) parts.push(lab(VF_LEN, VF.len).toLowerCase());
  if (VF.date === 'custom') parts.push(`${VF.from || '…'} to ${VF.to || 'today'}`); else if (VF.date) parts.push(lab(VF_DATE, VF.date).toLowerCase());
  if (VF.size) parts.push(lab(VF_SIZE, VF.size).toLowerCase());
  if (VF.text.trim()) parts.push(`name has "${VF.text.trim()}"`);
  return parts.join(' · ');
}

async function openVideoFilter() {
  const f = { ...VF };
  const body = el('div.vfilter');
  const group = (title, list, key, extra) => {
    const chips = el('div.row.wrap');
    const draw = () => { chips.innerHTML = ''; for (const [k, t] of list) chips.appendChild(el('div.chip' + (f[key] === k ? '.on' : ''), { onclick: () => { f[key] = k; draw(); if (extra) extra.style.display = f[key] === 'custom' ? '' : 'none'; } }, t)); };
    draw();
    body.append(el('div.label', title), chips);
    if (extra) { extra.style.display = f[key] === 'custom' ? '' : 'none'; body.appendChild(extra); }
  };
  const num = (k, ph) => { const i = el('input', { type: 'number', min: 0, step: 1, value: f[k], placeholder: ph, style: { width: '110px' } }); i.oninput = () => { f[k] = i.value; }; return i; };
  const date = (k) => { const i = el('input', { type: 'date', value: f[k] }); i.oninput = () => { f[k] = i.value; }; return i; };
  group('Length', VF_LEN, 'len', el('div.row', { style: { marginTop: '8px' } }, num('lenMin', 'from'), el('span.dim', 'to'), num('lenMax', 'to'), el('span.dim', 'minutes')));
  group('Date (when the file was saved or changed)', VF_DATE, 'date', el('div.row', { style: { marginTop: '8px' } }, date('from'), el('span.dim', 'to'), date('to')));
  group('Size', VF_SIZE, 'size');
  const t = el('input', { type: 'text', value: f.text, placeholder: 'Part of the name (optional)' });
  t.oninput = () => { f.text = t.value; };
  body.append(el('div.label', 'Name'), t);
  const v = await dialog({ title: 'Filter videos', body, buttons: [{ text: 'Clear', value: 'clear' }, { text: 'Cancel', value: false }, { text: 'Show', primary: true, value: 'ok' }] });
  if (v === 'clear') VF = vfEmpty();
  else if (v === 'ok') VF = f;
  else return;
  refreshView();
}

const thumbObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    thumbObserver.unobserve(e.target);
    const { v, badge } = e.target._thumb;
    api.thumb(v.path).then((t) => {
      if (t && t.thumb) { e.target.innerHTML = ''; e.target.style.background = `center/cover url("${t.thumb}")`; }
      if (badge && t && t.duration) badge.textContent = fmtTime(t.duration * 1000);
    });
  }
}, { rootMargin: '300px' });
function lazyThumb(node, v, folder, badge) { node._thumb = { v, badge }; thumbObserver.observe(node); }

function videoMenu(e, vs, i) {
  const v = vs[i];
  menu(e, [
    ['Play', () => openVideo(vs, i)],
    ['Edit video', () => go('veditor', { files: [v.path] })],
    ['Edit its sound (audio editor)', () => go('aeditor', { files: [v.path] })],
    ['Show in folder', () => api.showInFolder(v.path)],
  ]);
}

async function addVideoFolder() {
  const picked = await api.open({ title: 'Choose a folder with videos', properties: ['openDirectory', 'multiSelections'] });
  if (!picked.length) return;
  if (!L.videoDirs.length) L.videoDirs.push(...(await videoDirs()));
  for (const d of picked) if (!L.videoDirs.includes(d.path)) L.videoDirs.push(d.path);
  save();
  VIDS = null;
  refreshView();
}

async function openVideoFile() {
  const picked = await api.open({ title: 'Open a video or audio file', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Video and audio', extensions: ['mp4', 'mkv', 'webm', 'mov', 'avi', 'm4v', '3gp', 'wmv', 'flv', 'ts', 'mpg', 'mp3', 'm4a', 'wav', 'flac', 'ogg', 'opus', 'aac', 'wma'] }, { name: 'All files', extensions: ['*'] }] });
  if (picked.length) openFiles(picked);
}

async function openStream() {
  let u = await prompt('Open network stream', '', 'https://…  (video or audio link, .m3u8, .mp4 …)');
  if (!u) return;
  if (!/^\w+:\/\//.test(u)) u = 'https://' + u;
  openVideo([{ url: u, name: u.split('/').pop() || u, path: null }], 0);
}

/** Files opened with Echo (double-click, "Open with", drag & drop, Open file). */
function openFiles(files) {
  const audioFiles = files.filter((f) => f.audio);
  const videoFiles = files.filter((f) => !f.audio);
  if (audioFiles.length) {
    const list = audioFiles.map((f) => {
      const id = 'f:' + f.path.toLowerCase();
      return song(id) || { id, source: 'file', path: f.path, uri: f.url, title: f.name, artist: '', album: '', duration: 0 };
    });
    playList(list, 0);
  }
  if (videoFiles.length) openVideo(videoFiles.map((f) => ({ path: f.path, url: f.url, name: f.name })), 0);
}

// ---------- the player ----------
const V = { list: [], i: 0, zoom: 1, panX: 0, panY: 0, fit: 0, rotate: 0, idleTimer: null };

/** Puts the player into the current (video) tab. Use openVideo() (tabs.js) to open a video. */
function mountVideo(list, i) {
  unmountVideo(true);
  audio.pause(); // Echo's music stops while a video plays
  V.list = list; V.i = i; V.zoom = 1; V.panX = 0; V.panY = 0; V.rotate = 0;
  const video = el('video#vid', { autoplay: true });
  const hud = el('div.hud.hidden');
  const title = el('div.vtitle');
  const seek = el('input.fill', { type: 'range', min: 0, max: 1000, value: 0, style: { width: '100%' } });
  const pos = el('span', '0:00'), dur = el('span', '0:00');
  // hover the progress bar: a preview of that moment (like YouTube)
  const pvCanvas = el('canvas', { width: 240, height: 135 });
  const pvTime = el('span.pvtime');
  const pv = el('div.seekprev', pvCanvas, pvTime);
  const seekBox = el('div.seekwrap', seek, pv);
  const pvVid = document.createElement('video');
  pvVid.muted = true; pvVid.preload = 'auto';
  const PV = { busy: false, want: null, shown: null, cache: new Map() };
  function pvDraw(src, w, h) {
    const c = pvCanvas.getContext('2d');
    c.fillStyle = '#000'; c.fillRect(0, 0, 240, 135);
    if (!w || !h) return;
    const s = Math.min(240 / w, 135 / h);
    c.drawImage(src, (240 - w * s) / 2, (135 - h * s) / 2, w * s, h * s);
  }
  async function pvNext() {
    if (PV.busy || PV.want == null) return;
    const t = PV.want; PV.want = null; PV.busy = true;
    try {
      if (V.stream) { // played through Echo's converter: FFmpeg makes the picture
        const key = Math.round(t / 2) * 2;
        let url = PV.cache.get(key);
        if (!url) { url = await api.previewFrame(V.stream.path, key); if (url) PV.cache.set(key, url); }
        if (url) await new Promise((res) => { const im = new Image(); im.onload = () => { pvDraw(im, im.naturalWidth, im.naturalHeight); res(); }; im.onerror = res; im.src = url; });
      } else if (pvVid.readyState >= 1) {
        await new Promise((res) => { const done = () => { pvVid.removeEventListener('seeked', done); pvDraw(pvVid, pvVid.videoWidth, pvVid.videoHeight); res(); }; pvVid.addEventListener('seeked', done); pvVid.currentTime = t; setTimeout(done, 800); });
      }
    } catch (e) { /* no picture this time */ }
    PV.busy = false;
    pvNext();
  }
  seek.addEventListener('mousemove', (e) => {
    const d = V.length();
    if (!d || !isFinite(d)) return;
    const r = seek.getBoundingClientRect();
    const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    const t = f * d;
    pvTime.textContent = fmtTime(t * 1000);
    pv.style.left = Math.max(120, Math.min(r.width - 120, e.clientX - r.left)) + 'px';
    pv.classList.add('on');
    PV.want = t;
    pvNext();
  });
  seek.addEventListener('mouseleave', () => pv.classList.remove('on'));
  const playBtn = el('button.play', { onclick: () => toggleVideo() }, icon('pause'));
  const vol = el('input.fill', { type: 'range', min: 0, max: 100, value: Math.round((L.prefs.videoVolume == null ? 1 : L.prefs.videoVolume) * 100), style: { width: '110px' } });
  const speedBtn = el('button.btn.small', { onclick: (e) => speedMenu(e) }, '1x');
  const wrap = el('div#vplayer',
    video, hud,
    el('div.vtop', iconBtn('back', 'Close (Esc)', () => closeVideo()), title,
      iconBtn('cc', 'Subtitles file (.srt, .vtt)', loadSubs),
      iconBtn('aspect', 'Fit / Fill / Stretch (A)', nextFit),
      iconBtn('rotate', 'Rotate 90°', () => { V.rotate = (V.rotate + 90) % 360; applyZoom(); flash('Rotated ' + V.rotate + '°'); }),
      iconBtn('pip', 'Picture-in-picture (P)', () => video.requestPictureInPicture().catch(() => flash('Not available'))),
      iconBtn('more', 'More', videoMore)),
    el('div.vbottom',
      el('div.seek', pos, seekBox, dur),
      el('div.row', { style: { marginTop: '8px' } },
        iconBtn('prev', 'Previous (P)', () => stepVideo(-1)), playBtn, iconBtn('next', 'Next (N)', () => stepVideo(1)),
        el('span.dim', { style: { marginLeft: '10px' } }, 'Space play · ← → 5 s · ↑ ↓ volume · F full screen · scroll to zoom with Ctrl'),
        el('div.grow'), speedBtn, iconBtn('vol', 'Mute (M)', () => { video.muted = !video.muted; flash(video.muted ? 'Muted' : 'Sound on'); }), vol,
        iconBtn('full', 'Full screen (F)', () => api.fullscreen()))));
  $('#content').appendChild(wrap);
  paintRange(vol);
  video.volume = vol.value / 100;

  function load() {
    const item = V.list[V.i];
    title.textContent = item.name;
    if (typeof onVideoItem === 'function') onVideoItem(item);
    V.stream = null;
    api.streamStop();
    video.src = item.url;
    pvVid.src = item.url; // the hidden copy used for the hover preview
    PV.cache.clear();
    for (const t of $$('track', video)) t.remove();
    const key = item.path || item.url;
    const at = L.videoResume[key];
    video.onloadedmetadata = () => {
      if (V.stream) return;
      if (at && at > 5 && at < video.duration - 8) { video.currentTime = at; flash('Resumed at ' + fmtTime(at * 1000)); }
    };
    video.play().catch(() => {});
  }
  /** Formats Windows can't play (MKV, AVI, HEVC…): Echo converts them while they play. */
  async function startStream(at) {
    const item = V.list[V.i];
    const inf = await api.streamInfo(item.path);
    if (!inf || !inf.duration) return false;
    V.stream = { offset: 0, duration: inf.duration, path: item.path };
    streamSeek(at || 0);
    return true;
  }
  function streamSeek(t) {
    const s = V.stream;
    s.offset = Math.max(0, Math.min(t, s.duration - 1));
    video.src = 'echostream://play?file=' + encodeURIComponent(s.path) + '&t=' + s.offset.toFixed(2);
    video.onloadedmetadata = null;
    video.play().catch(() => {});
  }
  V.startStream = startStream;
  // the time in the video, whether it plays directly or through Echo's converter
  V.time = () => (V.stream ? V.stream.offset + video.currentTime : video.currentTime);
  V.length = () => (V.stream ? V.stream.duration : video.duration);
  V.seekTo = (t) => { t = Math.max(0, Math.min(t, (V.length() || 0) - 0.5)); if (V.stream) streamSeek(t); else video.currentTime = t; };
  V.load = load;
  function savePos() {
    const item = V.list[V.i];
    if (!item || !V.length()) return;
    const key = item.path || item.url;
    const t = V.time(), d = V.length();
    if (t > 5 && t < d - 8) L.videoResume[key] = Math.round(t);
    else delete L.videoResume[key];
    save();
  }
  V.savePos = savePos;
  video.addEventListener('timeupdate', () => {
    if (seek._drag) return;
    const d = V.length();
    seek.value = d ? Math.round(V.time() / d * 1000) : 0;
    paintRange(seek);
    pos.textContent = fmtTime(V.time() * 1000);
    dur.textContent = fmtTime(d * 1000);
    if (Math.random() < 0.03) savePos();
  });
  video.addEventListener('play', () => playBtn.firstChild.firstChild.setAttribute('href', '#i-pause'));
  video.addEventListener('pause', () => { playBtn.firstChild.firstChild.setAttribute('href', '#i-play'); savePos(); });
  video.addEventListener('ended', () => { delete L.videoResume[V.list[V.i].path || V.list[V.i].url]; save(); if (V.i < V.list.length - 1) stepVideo(1); });
  video.addEventListener('error', async () => {
    if (!V.stream && V.list[V.i] && V.list[V.i].path) { // try Echo's converter before giving up
      const at = L.videoResume[V.list[V.i].path];
      if (await startStream(at && at > 5 ? at : 0)) { flash(at > 5 ? 'Resumed at ' + fmtTime(at * 1000) : 'Playing with Echo\'s converter'); return; }
    }
    cantPlay();
  });
  seek.addEventListener('input', () => { seek._drag = true; paintRange(seek); pos.textContent = fmtTime(seek.value / 1000 * V.length() * 1000); });
  seek.addEventListener('change', () => { V.seekTo(seek.value / 1000 * V.length()); seek._drag = false; });
  V.setVolume = (x) => { vol.value = Math.round(Math.max(0, Math.min(1, x)) * 100); vol.dispatchEvent(new Event('input')); };
  // after using a slider with the mouse, the keys go back to the player
  for (const r of [seek, vol]) r.addEventListener('pointerup', () => setTimeout(() => r.blur(), 0));
  vol.addEventListener('input', () => { video.volume = vol.value / 100; video.muted = false; paintRange(vol); L.prefs.videoVolume = video.volume; save(); });

  // mouse: double-click full screen, wheel = volume, Ctrl+wheel = zoom, drag when zoomed = move
  video.addEventListener('dblclick', () => api.fullscreen());
  video.addEventListener('click', () => toggleVideo());
  wrap.addEventListener('wheel', (e) => {
    e.preventDefault();
    if (e.ctrlKey) {
      V.zoom = Math.max(1, Math.min(5, V.zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
      if (V.zoom < 1.03) { V.zoom = 1; V.panX = V.panY = 0; }
      applyZoom(); flash('Zoom ' + Math.round(V.zoom * 100) + '%');
    } else {
      const v = Math.max(0, Math.min(100, +vol.value + (e.deltaY < 0 ? 5 : -5)));
      vol.value = v; vol.dispatchEvent(new Event('input')); flash('Volume ' + v + '%');
    }
  }, { passive: false });
  let drag = null;
  video.addEventListener('mousedown', (e) => { if (V.zoom > 1) drag = { x: e.clientX, y: e.clientY, px: V.panX, py: V.panY }; });
  window.addEventListener('mousemove', V.onMove = (e) => {
    wrap.classList.remove('idle');
    clearTimeout(V.idleTimer);
    V.idleTimer = setTimeout(() => wrap.classList.add('idle'), 2500);
    if (drag) { V.panX = drag.px + e.clientX - drag.x; V.panY = drag.py + e.clientY - drag.y; applyZoom(); }
  });
  window.addEventListener('mouseup', V.onUp = () => { drag = null; });
  // touch screens: pinch to zoom
  let pinch = null;
  wrap.addEventListener('touchstart', (e) => { if (e.touches.length === 2) pinch = { d: dist(e.touches), z: V.zoom }; }, { passive: true });
  wrap.addEventListener('touchmove', (e) => {
    if (pinch && e.touches.length === 2) { V.zoom = Math.max(1, Math.min(5, pinch.z * dist(e.touches) / pinch.d)); applyZoom(); flash('Zoom ' + Math.round(V.zoom * 100) + '%'); }
  }, { passive: true });
  wrap.addEventListener('touchend', () => { pinch = null; });
  function dist(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }

  function speedMenu(e) {
    menu(e, [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3].map((s) => [s === 1 ? 'Normal' : s + 'x', () => {
      video.playbackRate = s; speedBtn.textContent = s + 'x'; flash(s === 1 ? 'Normal speed' : s + 'x');
    }]));
  }
  function videoMore(e) {
    menu(e, [
      ['Audio track…', chooseAudioTrack],
      ['Subtitles file…', loadSubs],
      ['Repeat this video: ' + (video.loop ? 'on' : 'off'), () => { video.loop = !video.loop; flash(video.loop ? 'Repeat on' : 'Repeat off'); }],
      ['Play as audio (keeps playing when you close the video)', playVideoAsAudio],
      ['Keep playing when I close the video: ' + (L.prefs.videoBackground ? 'ON' : 'off'), () => { L.prefs.videoBackground = !L.prefs.videoBackground; save(); flash(L.prefs.videoBackground ? 'The sound carries on when you close the video' : 'Video stops when you close it'); }],
      ['Sleep timer…', sleepTimer],
      ['Take a picture (snapshot)', snapshot],
      V.list[V.i].path ? ['Edit this video', () => { const p = V.list[V.i].path; closeVideo(); go('veditor', { files: [p] }); }] : null,
      V.list[V.i].path ? ['Show in folder', () => api.showInFolder(V.list[V.i].path)] : null,
    ]);
  }
  function chooseAudioTrack() {
    const t = video.audioTracks;
    if (!t || t.length < 2) { flash('This video has one audio track'); return; }
    menu({ clientX: innerWidth - 300, clientY: 60, preventDefault() {}, stopPropagation() {} },
      Array.from(t).map((tr, k) => [(tr.label || tr.language || 'Track ' + (k + 1)) + (tr.enabled ? '  ✓' : ''), () => { for (let j = 0; j < t.length; j++) t[j].enabled = j === k; }]));
  }
  load();
  wrap.classList.remove('idle');
}

function flash(text) {
  const h = $('#vplayer .hud');
  if (!h) { toast(text); return; }
  h.textContent = text;
  h.classList.remove('hidden');
  clearTimeout(h._t);
  h._t = setTimeout(() => h.classList.add('hidden'), 900);
}
function vid() { return $('#vid'); }
function toggleVideo() { const v = vid(); if (!v) return; if (v.paused) v.play(); else v.pause(); }
function stepVideo(d) {
  const j = V.i + d;
  if (j < 0 || j >= V.list.length) { flash(d > 0 ? 'Last video' : 'First video'); return; }
  V.savePos(); V.i = j; V.zoom = 1; V.panX = V.panY = 0; applyZoom(); V.load();
}
function nextFit() {
  V.fit = (V.fit + 1) % 3;
  vid().style.objectFit = ['contain', 'cover', 'fill'][V.fit];
  V.zoom = 1; V.panX = V.panY = 0; applyZoom();
  flash(['Fit screen', 'Fill (crop)', 'Stretch'][V.fit]);
}
function applyZoom() {
  const v = vid();
  if (!v) return;
  const maxX = innerWidth * (V.zoom - 1) / 2, maxY = innerHeight * (V.zoom - 1) / 2;
  V.panX = Math.max(-maxX, Math.min(maxX, V.panX));
  V.panY = Math.max(-maxY, Math.min(maxY, V.panY));
  v.style.transform = `translate(${V.panX}px, ${V.panY}px) scale(${V.zoom}) rotate(${V.rotate}deg)`;
}
async function loadSubs() {
  const picked = await api.open({ title: 'Choose a subtitle file', properties: ['openFile'], filters: [{ name: 'Subtitles', extensions: ['srt', 'vtt'] }] });
  if (!picked.length) return;
  try {
    let text = await api.readText(picked[0].path);
    if (!/^WEBVTT/.test(text)) text = 'WEBVTT\n\n' + text.replace(/\r/g, '').replace(/(\d+:\d+:\d+),(\d+)/g, '$1.$2');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
    const v = vid();
    for (const t of $$('track', v)) t.remove();
    const tr = el('track', { kind: 'subtitles', src: url, default: true, label: 'Subtitles' });
    v.appendChild(tr);
    setTimeout(() => { if (v.textTracks[0]) v.textTracks[0].mode = 'showing'; }, 100);
    flash('Subtitles added');
  } catch (e) { flash('Couldn\'t read that subtitle file'); }
}
let sleepT = null;
async function sleepTimer() {
  const mins = [15, 30, 45, 60, 90];
  const i = await choose('Stop playing after', [...mins.map((m) => m + ' minutes'), 'Turn off']);
  clearTimeout(sleepT);
  if (i >= 0 && i < mins.length) { sleepT = setTimeout(() => { if (vid()) vid().pause(); audio.pause(); }, mins[i] * 60000); toast('Sleep timer: ' + mins[i] + ' minutes'); }
  else if (i === mins.length) toast('Sleep timer off');
}
async function snapshot() {
  const v = vid();
  const c = document.createElement('canvas');
  c.width = v.videoWidth; c.height = v.videoHeight;
  c.getContext('2d').drawImage(v, 0, 0);
  const info = await api.info();
  const out = await api.saveDialog({ title: 'Save picture', defaultPath: info.downloads + '\\Echo snapshot ' + Date.now() + '.png', filters: [{ name: 'PNG', extensions: ['png'] }] });
  if (!out) return;
  await api.writeData(out, c.toDataURL('image/png'));
  flash('Picture saved');
}
function playVideoAsAudio() {
  const item = V.list[V.i];
  const t = V.time ? V.time() : vid().currentTime;
  closeVideo(true);
  const s = { id: 'v:' + (item.path || item.url), source: 'file', path: item.path, uri: item.url, title: item.name, artist: 'Video', album: '', duration: 0 };
  playList([s], 0);
  setTimeout(() => { audio.currentTime = t; }, 400);
}
async function cantPlay() {
  const item = V.list[V.i];
  if (!item.path) { flash('Can\'t play this stream'); return; }
  const ok = await confirmBox('This video can\'t play here',
    'Echo couldn\'t play this file, even with its converter (the file may be damaged or incomplete).\n\nTry making a normal MP4 copy with FFmpeg? The original stays as it is.', 'Make a copy');
  if (!ok) return;
  const out = item.path.replace(/\.[^.\\/]+$/, '') + ' (Echo).mp4';
  const info = await api.probe(item.path);
  const job = 'conv' + Date.now();
  const prog = progressBox('Converting ' + item.name, () => api.ffCancel(job));
  const off = api.onFfProgress((id, pct) => { if (id === job) prog.set(pct, pct + '%'); });
  const r = await api.ffExport({ id: job, kind: 'convert', file: item.path, out, total: info.duration });
  prog.close();
  if (r.ok) { item.path = r.out; item.url = r.url; item.name = item.name + ' (Echo)'; V.load(); VIDS = null; }
  else if (!r.cancelled) alertBox('Couldn\'t convert', r.error);
}
function unmountVideo(silent) {
  const w = $('#vplayer');
  if (!w) return;
  if (V.savePos) V.savePos();
  const v = vid();
  if (v && document.pictureInPictureElement === v) document.exitPictureInPicture().catch(() => {});
  if (L.prefs.videoBackground && v && !v.paused && !silent) { playVideoAsAudio(); return; }
  if (V.stream) { V.stream = null; api.streamStop(); }
  for (const pvv of $$('video', w)) { pvv.removeAttribute('src'); pvv.load(); }
  window.removeEventListener('mousemove', V.onMove);
  window.removeEventListener('mouseup', V.onUp);
  w.remove();
  api.fullscreen(false);
}
