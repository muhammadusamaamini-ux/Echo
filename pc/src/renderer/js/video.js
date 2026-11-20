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
  main.appendChild(head(folderPath ? folderPath.split(/[\\/]/).pop() : 'Videos', viewToggle(),
    folderPath ? null : el('button.btn', { onclick: addVideoFolder }, '+ Video folder'),
    el('button.btn', { onclick: openVideoFile }, 'Open file'),
    el('button.btn', { onclick: openStream }, 'Network stream'),
    el('button.btn.primary', { onclick: () => go('veditor') }, '✂ Edit a video')));
  const box = el('div', el('div.empty', 'Looking for videos…'));
  main.appendChild(box);
  scanVideos(!folderPath && !VIDS).then((vids) => {
    if (NAV.view !== 'videos') return;
    box.innerHTML = '';
    if (!folderPath) {
      const groups = new Map();
      for (const v of vids) { if (!groups.has(v.folderPath)) groups.set(v.folderPath, []); groups.get(v.folderPath).push(v); }
      box.appendChild(el('div.sub', { style: { marginBottom: '14px' } },
        `${plural(vids.length, 'video')} in ${plural(groups.size, 'folder')} · looking in: `,
        ...L.videoDirs.length ? L.videoDirs.map((d, i) => el('span', i ? ', ' : '', el('a', { style: { color: 'var(--glow)', cursor: 'pointer' }, title: 'Right-click to stop looking here', oncontextmenu: (e) => menu(e, [['Stop looking in this folder', () => { L.videoDirs.splice(i, 1); save(); VIDS = null; refreshView(); }]]) }, d))) : ['your Videos and Downloads folders']));
      if (!vids.length) { box.appendChild(el('div.empty', 'No videos found. Use "+ Video folder" to choose where your videos are, or "Open file".')); return; }
      const list = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
      const wrap = el('div.cards.video');
      for (const [fp, vs] of list) {
        const art = el('div.art', { style: { background: grad(fp) } }, icon('folder'));
        wrap.appendChild(el('div.card', { onclick: () => go('videos', { folder: fp }) },
          el('div.art-wrap', art), el('div.name', fp.split(/[\\/]/).pop()), el('div.meta', plural(vs.length, 'video') + ' · ' + fp)));
        lazyThumb(art, vs[0], true);
      }
      box.appendChild(wrap);
      return;
    }
    const vs = vids.filter((v) => v.folderPath === folderPath);
    if (!vs.length) { box.appendChild(el('div.empty', 'No videos here.')); return; }
    if (L.prefs.grid) {
      const wrap = el('div.cards.video');
      vs.forEach((v, i) => {
        const art = el('div.art', { style: { background: grad(v.name) } }, icon('movie'));
        const badge = el('div.badge', '');
        wrap.appendChild(el('div.card', { onclick: () => openVideo(vs, i), oncontextmenu: (e) => videoMenu(e, vs, i) },
          el('div.art-wrap', art, badge), el('div.name', v.name), el('div.meta', fmtSize(v.size) + ' · ' + new Date(v.mtime).toLocaleDateString())));
        lazyThumb(art, v, false, badge);
      });
      box.appendChild(wrap);
    } else {
      const list = el('div.list');
      vs.forEach((v, i) => {
        const art = el('div.ico', { style: { width: '96px', height: '54px', background: grad(v.name) } }, icon('movie'));
        const dur = el('div.dur', '');
        list.appendChild(el('div.item', { style: { gridTemplateColumns: '36px 100px 1fr 90px 70px 40px' }, onclick: () => openVideo(vs, i), oncontextmenu: (e) => videoMenu(e, vs, i) },
          el('div.num', String(i + 1)), art, el('div', el('div.title', v.name), el('div.s', new Date(v.mtime).toLocaleString())),
          el('div.dur', fmtSize(v.size)), dur, el('button.icon-btn', { onclick: (e) => videoMenu(e, vs, i) }, icon('more'))));
        lazyThumb(art, v, false, dur);
      });
      box.appendChild(list);
    }
  });
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
      el('div.seek', pos, seek, dur),
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
    video.src = item.url;
    for (const t of $$('track', video)) t.remove();
    const key = item.path || item.url;
    const at = L.videoResume[key];
    video.onloadedmetadata = () => {
      if (at && at > 5 && at < video.duration - 8) { video.currentTime = at; flash('Resumed at ' + fmtTime(at * 1000)); }
    };
    video.play().catch(() => {});
  }
  V.load = load;
  function savePos() {
    const item = V.list[V.i];
    if (!item || !video.duration) return;
    const key = item.path || item.url;
    if (video.currentTime > 5 && video.currentTime < video.duration - 8) L.videoResume[key] = Math.round(video.currentTime);
    else delete L.videoResume[key];
    save();
  }
  V.savePos = savePos;
  video.addEventListener('timeupdate', () => {
    if (seek._drag) return;
    seek.value = video.duration ? Math.round(video.currentTime / video.duration * 1000) : 0;
    paintRange(seek);
    pos.textContent = fmtTime(video.currentTime * 1000);
    dur.textContent = fmtTime(video.duration * 1000);
    if (Math.random() < 0.03) savePos();
  });
  video.addEventListener('play', () => playBtn.firstChild.firstChild.setAttribute('href', '#i-pause'));
  video.addEventListener('pause', () => { playBtn.firstChild.firstChild.setAttribute('href', '#i-play'); savePos(); });
  video.addEventListener('ended', () => { delete L.videoResume[V.list[V.i].path || V.list[V.i].url]; save(); if (V.i < V.list.length - 1) stepVideo(1); });
  video.addEventListener('error', () => cantPlay());
  seek.addEventListener('input', () => { seek._drag = true; paintRange(seek); pos.textContent = fmtTime(seek.value / 1000 * video.duration * 1000); });
  seek.addEventListener('change', () => { video.currentTime = seek.value / 1000 * video.duration; seek._drag = false; });
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
  const t = vid().currentTime;
  closeVideo(true);
  const s = { id: 'v:' + (item.path || item.url), source: 'file', path: item.path, uri: item.url, title: item.name, artist: 'Video', album: '', duration: 0 };
  playList([s], 0);
  setTimeout(() => { audio.currentTime = t; }, 400);
}
async function cantPlay() {
  const item = V.list[V.i];
  if (!item.path) { flash('Can\'t play this stream'); return; }
  const ok = await confirmBox('This video can\'t play here',
    'Windows can\'t play this video\'s format directly (for example some MKV, AVI or HEVC files).\n\nConvert it to a normal MP4 copy with FFmpeg? The original stays as it is.', 'Convert');
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
  window.removeEventListener('mousemove', V.onMove);
  window.removeEventListener('mouseup', V.onUp);
  w.remove();
  api.fullscreen(false);
}
