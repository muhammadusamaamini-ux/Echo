// Audio editor: waveform, trim, split, cut out, join, speed, pitch, volume, fade, mix; saved with FFmpeg.
'use strict';

const AE = { parts: [], sel: -1, fadeIn: 0, fadeOut: 0, mix: null, mixVolume: 0.5, cutMark: null };
const aePart = () => AE.parts[AE.sel] || null;
const partLen = (p) => Math.max(0.1, (p.end - p.start) / p.speed);

async function addAudio(items) {
  // items: [{ path, url, name }] — local files or Drive links
  const prog = progressBox('Adding audio');
  let k = 0;
  for (const it of items) {
    prog.set(Math.round(k++ / items.length * 100), 'Reading ' + it.name + '…');
    const info = await api.probe(it.path);
    const p = { path: it.path, url: it.url, name: it.name, sourceSec: info.duration || 0, start: 0, end: info.duration || 0,
      speed: 1, pitch: 1, volume: 1, wave: null };
    if (!info.duration) toast('Couldn\'t read ' + it.name);
    const at = AE.sel < 0 ? AE.parts.length : AE.sel + 1;
    AE.parts.splice(at, 0, p);
    AE.sel = at;
    api.waveform(it.path).then((w) => { p.wave = w; if (NAV.view === 'aeditor' && aePart() === p) drawWave(); });
  }
  prog.close();
  if (NAV.view === 'aeditor') refreshView();
}

async function pickAudio() {
  const picked = await api.open({ title: 'Add audio (or videos, for their sound)', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Audio and video', extensions: ['mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'opus', 'wma', 'mp4', 'mkv', 'webm', 'mov', 'avi'] }] });
  if (picked.length) addAudio(picked.map((f) => ({ path: f.path, url: f.url, name: f.name })));
}
async function pickFromSongs() {
  const all = sortedSongs();
  const i = await choose('Add a song from your library', all.slice(0, 400).map((s) => s.title + (s.artist ? '  —  ' + s.artist : '')));
  if (i >= 0) addAudio([songToItem(all[i])]);
}
function songToItem(s) { return { path: s.source === 'file' ? s.path : s.uri, url: s.uri, name: s.title }; }

function audioEditorView(arg) {
  if (arg) {
    NAV.arg = null;
    if (arg.songs) addAudio(arg.songs.map(songToItem));
    else if (arg.files) addAudio(arg.files.map((f) => ({ path: f, url: null, name: f.split(/[\\/]/).pop().replace(/\.[^.]+$/, '') })));
  }
  main.appendChild(head('Audio editor',
    el('button.btn', { onclick: async () => { if (AE.parts.length && !(await confirmBox('New project', 'Start again with nothing?', 'Start again', true))) return; Object.assign(AE, { parts: [], sel: -1, fadeIn: 0, fadeOut: 0, mix: null, cutMark: null }); refreshView(); } }, 'New'),
    el('button.btn', { onclick: pickFromSongs }, '+ From my songs'),
    el('button.btn', { onclick: pickAudio }, '+ Add audio files'),
    el('button.btn.primary', { onclick: saveAudio }, 'Save')));
  if (!AE.parts.length) {
    main.appendChild(el('div.empty', { html: 'Add a song to cut, trim or join.<br><br>You can also right-click any song in Echo and choose <b>Edit audio</b>.' }));
    return;
  }
  const p = aePart();
  const player = el('audio#aePlayer', { src: p.url || '', preload: 'auto' });
  if (!p.url) api.fileUrl(p.path).then((u) => { p.url = u; player.src = u; });
  const time = el('div', { style: { fontSize: '34px', fontWeight: '800', color: 'var(--glow)', fontVariantNumeric: 'tabular-nums' } }, '0:00');
  const wave = el('div.wave', el('canvas#aeWave'));
  const playB = el('button.play', { onclick: () => { if (player.paused) player.play(); else player.pause(); } }, icon('play'));
  main.appendChild(el('div.glass', { style: { padding: '16px 18px' } },
    el('div.row', el('b.grow', p.name), el('span.sub', `Part ${AE.sel + 1} of ${AE.parts.length} · ${fmtTime((p.end - p.start) * 1000)} of ${fmtTime(p.sourceSec * 1000)}`)),
    el('div.row', { style: { margin: '10px 0' } }, playB, el('button.btn.small', { onclick: () => nudge(-5) }, '−5 s'),
      el('button.btn.small', { onclick: () => nudge(5) }, '+5 s'), time, el('div.grow'),
      el('button.btn.small', { onclick: startHere }, '[ Start here'), el('button.btn.small', { onclick: endHere }, 'End here ]')),
    wave,
    el('div.dim', { style: { fontSize: '12px', marginTop: '8px' } }, 'Click the waveform to jump there. Drag the cyan edges to trim. Keys: Space play, [ start here, ] end here, S split.'),
    player));
  // parts strip
  const strip = el('div.parts');
  AE.parts.forEach((x, i) => strip.appendChild(el('div.part' + (i === AE.sel ? '.on' : ''), { style: { background: grad(x.name) }, onclick: () => { AE.sel = i; AE.cutMark = null; refreshView(); } },
    el('b', `${i + 1}. ${x.name}`), el('div', { style: { fontSize: '12px' } }, fmtTime(partLen(x) * 1000) + (x.speed !== 1 ? '  ' + x.speed + 'x' : '')))));
  strip.appendChild(el('div.part.glass', { onclick: pickAudio }, el('b', '+ Add')));
  main.appendChild(strip);
  const total = AE.parts.reduce((t, x) => t + partLen(x), 0);
  main.appendChild(el('div.sub', `Total ${fmtTime(total * 1000)}` + (AE.fadeIn || AE.fadeOut ? ` · fade ${AE.fadeIn}s / ${AE.fadeOut}s` : '') + (AE.mix ? ' · mixed with ' + AE.mix.name : '') + (AE.cutMark != null ? ' · ✂ cut start marked at ' + fmtTime(AE.cutMark * 1000) : '')));
  const tool = (ic, name, fn) => el('div.tool', { onclick: fn }, icon(ic), name);
  main.appendChild(el('div.tools', { style: { marginTop: '14px' } },
    el('div.grid', { style: { gridTemplateColumns: 'repeat(auto-fill, minmax(110px, 1fr))' } },
      tool('cut', 'Split here', splitHere), tool('cut', 'Cut out part', cutOut), tool('close', 'Delete part', () => { AE.parts.splice(AE.sel, 1); AE.sel = Math.min(AE.sel, AE.parts.length - 1); refreshView(); }),
      tool('add', 'Duplicate', () => { AE.parts.splice(AE.sel + 1, 0, { ...aePart() }); AE.sel++; refreshView(); }),
      tool('back', 'Move left', () => moveP(-1)), tool('next', 'Move right', () => moveP(1)),
      tool('speed', 'Speed', aeSpeed), tool('music', 'Voice / pitch', aeVoice), tool('vol', 'Volume', aeVolume),
      tool('wave', 'Fade in / out', aeFade), tool('music', 'Mix with music', aeMix))));
  main.appendChild(el('div.dim', { style: { marginTop: '14px', fontSize: '12px' } },
    'To remove the middle of a song: play to where it should start and click "Cut out part", then play to where it should end and click it again.'));

  player.addEventListener('loadedmetadata', () => { player.currentTime = p.start; player.playbackRate = p.speed; player.preservesPitch = p.pitch === 1; player.volume = Math.min(1, p.volume); });
  player.addEventListener('timeupdate', () => {
    if (player.currentTime >= p.end) { player.pause(); player.currentTime = p.start; }
    time.textContent = fmtTime((player.currentTime - p.start) * 1000);
    drawWave();
  });
  player.addEventListener('play', () => playB.firstChild.firstChild.setAttribute('href', '#i-pause'));
  player.addEventListener('pause', () => playB.firstChild.firstChild.setAttribute('href', '#i-play'));
  setupWaveDrag();
  requestAnimationFrame(drawWave);
}

function drawWave() {
  const c = $('#aeWave'), p = aePart(), pl = $('#aePlayer');
  if (!c || !p) return;
  const w = c.clientWidth, h = c.clientHeight;
  if (c.width !== w * devicePixelRatio) { c.width = w * devicePixelRatio; c.height = h * devicePixelRatio; }
  const g = c.getContext('2d');
  g.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
  g.clearRect(0, 0, w, h);
  const dur = p.sourceSec || 1;
  const x = (t) => t / dur * w;
  const peaks = p.wave || [];
  const grd = g.createLinearGradient(0, 0, w, 0);
  grd.addColorStop(0, '#00d2ff'); grd.addColorStop(1, '#9d4edd');
  if (peaks.length) {
    const bw = w / peaks.length;
    for (let i = 0; i < peaks.length; i++) {
      const bh = Math.max(1, peaks[i] * (h - 16));
      const tx = i * bw;
      g.fillStyle = (tx >= x(p.start) && tx <= x(p.end)) ? grd : 'rgba(255,255,255,.18)';
      g.fillRect(tx, (h - bh) / 2, Math.max(1, bw - 0.5), bh);
    }
  } else {
    g.fillStyle = 'rgba(255,255,255,.3)'; g.font = '13px Segoe UI'; g.fillText('Drawing the waveform…', 14, h / 2);
  }
  g.fillStyle = 'rgba(0,0,0,.35)';
  g.fillRect(0, 0, x(p.start), h);
  g.fillRect(x(p.end), 0, w - x(p.end), h);
  g.fillStyle = '#7df9ff';
  g.fillRect(x(p.start) - 2, 0, 4, h);
  g.fillRect(x(p.end) - 2, 0, 4, h);
  if (AE.cutMark != null) { g.fillStyle = '#ff4d6d'; g.fillRect(x(AE.cutMark) - 1, 0, 2, h); }
  if (pl) { g.fillStyle = '#fff'; g.fillRect(x(pl.currentTime) - 1, 0, 2, h); }
}

function setupWaveDrag() {
  const c = $('#aeWave');
  if (!c) return;
  let mode = null;
  const tAt = (e) => { const r = c.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * (aePart().sourceSec || 0); };
  c.addEventListener('mousedown', (e) => {
    const p = aePart(); const t = tAt(e); const near = (p.sourceSec || 1) * 0.015;
    if (Math.abs(t - p.start) < near) mode = 'start';
    else if (Math.abs(t - p.end) < near) mode = 'end';
    else { mode = null; const pl = $('#aePlayer'); pl.currentTime = Math.max(p.start, Math.min(p.end - 0.05, t)); drawWave(); }
  });
  c.addEventListener('mousemove', (e) => {
    const p = aePart(); const t = tAt(e); const near = (p.sourceSec || 1) * 0.015;
    c.style.cursor = (Math.abs(t - p.start) < near || Math.abs(t - p.end) < near || mode) ? 'ew-resize' : 'pointer';
    if (!mode) return;
    if (mode === 'start') p.start = Math.min(t, p.end - 0.3); else p.end = Math.max(t, p.start + 0.3);
    drawWave();
  });
  window.addEventListener('mouseup', () => { if (mode) { mode = null; refreshView(); } }, { once: false });
}

function here() { const pl = $('#aePlayer'); return pl ? pl.currentTime : 0; }
function nudge(s) { const pl = $('#aePlayer'), p = aePart(); pl.currentTime = Math.max(p.start, Math.min(p.end - 0.05, pl.currentTime + s)); }
function startHere() { const p = aePart(), t = here(); if (t >= p.end - 0.3) { toast('That\'s at the very end'); return; } p.start = t; toast('Starts at ' + fmtTime(t * 1000)); refreshView(); }
function endHere() { const p = aePart(), t = here(); if (t <= p.start + 0.3) { toast('That\'s at the very start'); return; } p.end = t; toast('Ends at ' + fmtTime(t * 1000)); refreshView(); }
function splitHere() {
  const p = aePart(), t = here();
  if (t <= p.start + 0.3 || t >= p.end - 0.3) { toast('Play to where you want to split, then click Split here'); return; }
  const b = { ...p, start: t };
  p.end = t;
  AE.parts.splice(AE.sel + 1, 0, b);
  toast('Split at ' + fmtTime(t * 1000) + '. Now there are 2 parts.');
  refreshView();
}
function cutOut() {
  const p = aePart(), t = here();
  if (AE.cutMark == null) { AE.cutMark = t; toast('Cut start marked at ' + fmtTime(t * 1000) + '. Now play to its end and click "Cut out part" again.', 5000); refreshView(); return; }
  let a = Math.min(AE.cutMark, t), z = Math.max(AE.cutMark, t);
  AE.cutMark = null;
  if (z - a < 0.2) { toast('The section is too short. Try again.'); refreshView(); return; }
  a = Math.max(a, p.start); z = Math.min(z, p.end);
  const keep = [];
  if (a - p.start >= 0.2) keep.push({ ...p, end: a });
  if (p.end - z >= 0.2) keep.push({ ...p, start: z });
  AE.parts.splice(AE.sel, 1, ...keep);
  AE.sel = Math.min(AE.sel, AE.parts.length - 1);
  toast(`Cut out ${fmtTime((z - a) * 1000)} (${fmtTime(a * 1000)} – ${fmtTime(z * 1000)})`);
  refreshView();
}
function moveP(d) {
  const j = AE.sel + d;
  if (j < 0 || j >= AE.parts.length) return;
  const [p] = AE.parts.splice(AE.sel, 1); AE.parts.splice(j, 0, p); AE.sel = j; refreshView();
}
async function aeSpeed() {
  const v = [0.5, 0.75, 1, 1.25, 1.5, 2];
  const i = await choose('Speed', v.map((x) => x === 1 ? 'Normal' : x + 'x'));
  if (i >= 0) { aePart().speed = v[i]; refreshView(); }
}
async function aeVoice() {
  const v = [1, 1.5, 1.25, 0.85, 0.65], n = ['Normal', 'Chipmunk', 'Higher', 'Deeper', 'Monster'];
  const i = await choose('Voice / pitch', n);
  if (i >= 0) { aePart().pitch = v[i]; toast('Voice: ' + n[i] + ' (you\'ll hear it in the saved file)'); refreshView(); }
}
async function aeVolume() {
  const box = el('div');
  const r = sliderRow(box, 'Volume of this part', 0, 300, Math.round(aePart().volume * 100), '%');
  if (await dialog({ title: 'Volume', body: box, buttons: [{ text: 'Cancel', value: false }, { text: 'OK', primary: true, value: true }] })) { aePart().volume = r.value / 100; refreshView(); }
}
async function aeFade() {
  const box = el('div');
  const a = sliderRow(box, 'Fade in at the start', 0, 10, AE.fadeIn, ' s');
  const b = sliderRow(box, 'Fade out at the end', 0, 10, AE.fadeOut, ' s');
  if (await dialog({ title: 'Fade', body: box, buttons: [{ text: 'Cancel', value: false }, { text: 'OK', primary: true, value: true }] })) { AE.fadeIn = +a.value; AE.fadeOut = +b.value; refreshView(); }
}
async function aeMix() {
  if (!AE.mix) {
    const picked = await api.open({ title: 'Choose a sound to play under your audio', properties: ['openFile'], filters: [{ name: 'Audio', extensions: ['mp3', 'm4a', 'wav', 'aac', 'flac', 'ogg'] }] });
    if (!picked.length) return;
    AE.mix = { path: picked[0].path, name: picked[0].name };
  }
  const box = el('div', el('b', '♪ ' + AE.mix.name));
  const r = sliderRow(box, 'Its volume', 0, 150, Math.round(AE.mixVolume * 100), '%');
  const v = await dialog({ title: 'Mix with music', body: box, buttons: [{ text: 'Remove', danger: true, value: 'remove' }, { text: 'OK', primary: true, value: true }] });
  if (v === 'remove') AE.mix = null; else if (v) AE.mixVolume = r.value / 100;
  refreshView();
}

async function saveAudio() {
  if (!AE.parts.length) { toast('Add audio first'); return; }
  const opts = [AE.parts.length > 1 ? 'Join all parts into one file' : 'Save as a new file'];
  if (AE.parts.length > 1) opts.push(`Save each part as its own file (${AE.parts.length} files)`);
  const which = await choose('Save audio', opts);
  if (which < 0) return;
  const fi = await choose('Format', ['MP3 (plays everywhere)', 'M4A (AAC)', 'WAV (no compression, big file)']);
  if (fi < 0) return;
  const fmt = ['mp3', 'm4a', 'wav'][fi];
  const info = await api.info();
  const base = AE.parts[0].name.replace(/[\\/:*?"<>|]/g, '_');
  const jobs = [];
  if (which === 0) {
    const out = await api.saveDialog({ title: 'Save audio', defaultPath: `${info.music}\\${base} (edited).${fmt}`, filters: [{ name: fmt.toUpperCase(), extensions: [fmt] }] });
    if (!out) return;
    jobs.push({ parts: AE.parts, out, mix: true });
  } else {
    const dir = await api.open({ title: 'Choose where to save the parts', properties: ['openDirectory', 'createDirectory'] });
    if (!dir.length) return;
    AE.parts.forEach((p, i) => jobs.push({ parts: [p], out: `${dir[0].path}\\${base} part ${i + 1}.${fmt}`, mix: false }));
  }
  const saved = [];
  for (let k = 0; k < jobs.length; k++) {
    const j = jobs[k];
    const id = 'a' + Date.now() + k;
    const prog = progressBox(jobs.length > 1 ? `Saving part ${k + 1} of ${jobs.length}` : 'Saving audio', () => api.ffCancel(id));
    api.onFfProgress((x, pct) => { if (x === id) prog.set(pct, pct + '%'); });
    const project = { parts: j.parts.map((p) => ({ path: p.path, start: p.start, end: p.end, speed: p.speed, pitch: p.pitch, volume: p.volume })),
      fadeIn: AE.fadeIn, fadeOut: AE.fadeOut, mix: j.mix && AE.mix ? AE.mix.path : null, mixVolume: AE.mixVolume };
    const r = await api.ffExport({ id, kind: 'audio', project, out: j.out, format: fmt });
    prog.close();
    if (!r.ok) { if (!r.cancelled) alertBox('Couldn\'t save', r.error); return; }
    const title = j.out.split(/[\\/]/).pop().replace(/\.[^.]+$/, '');
    const s = { id: 'f:' + j.out.toLowerCase(), source: 'file', path: j.out, uri: r.url, title, artist: 'Edited audio', album: '', duration: Math.round(j.parts.reduce((t, p) => t + partLen(p), 0) * 1000), added: Date.now() };
    if (!addSong(s)) Object.assign(song(s.id), s);
    saved.push(s);
  }
  save();
  const v = await dialog({ title: saved.length > 1 ? `Saved ${saved.length} files` : 'Audio saved',
    body: esc(jobs.map((j) => j.out).join('\n')).replace(/\n/g, '<br>') + '<br><br>They\'re also in your Echo songs.',
    buttons: [{ text: 'Show in folder', value: 'show' }, { text: 'Keep editing', value: false }, { text: 'Play', primary: true, value: 'play' }] });
  if (v === 'show') api.showInFolder(jobs[0].out);
  else if (v === 'play') playList(saved, 0);
}
