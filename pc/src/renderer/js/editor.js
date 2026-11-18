// Video editor (CapCut / YouCut style), exported with FFmpeg.
'use strict';

const FILTERS = ['None', 'Black & White', 'Sepia', 'Vintage', 'Warm', 'Cool', 'Vivid', 'Dramatic', 'Noir', 'Invert'];
const CSS_FILTER = {
  'Black & White': 'grayscale(1)', 'Sepia': 'sepia(1)', 'Vintage': 'sepia(.45) contrast(.9) saturate(.8)',
  'Warm': 'sepia(.2) saturate(1.25)', 'Cool': 'hue-rotate(12deg) saturate(1.1) brightness(1.02)', 'Vivid': 'saturate(1.4) contrast(1.1)',
  'Dramatic': 'contrast(1.35) saturate(.85)', 'Noir': 'grayscale(1) contrast(1.4)', 'Invert': 'invert(1)',
};
const VE = newProject();
function newProject() {
  return { clips: [], sel: -1, rotate: 0, flipH: false, flipV: false, filter: 'None', brightness: 0, contrast: 0, saturation: 0,
    aspect: 0, aspectFill: true, quality: 1080, texts: [], sticker: null, music: null, musicVolume: 0.8, originalVolume: 1 };
}
function leavingEditor() { return true; } // the project stays open while you visit other screens

const curClip = () => VE.clips[VE.sel] || null;
const clipLen = (c) => c.image ? c.photoSec : Math.max(0.1, (c.end - c.start) / c.speed);

async function addClips(paths) {
  const prog = progressBox('Adding');
  let k = 0;
  for (const p of paths) {
    prog.set(Math.round(k++ / paths.length * 100), 'Reading ' + p.split(/[\\/]/).pop() + '…');
    const image = /\.(jpe?g|png|webp|bmp|gif)$/i.test(p);
    const url = await api.fileUrl(p);
    const c = { path: p, url, name: p.split(/[\\/]/).pop(), image, speed: 1, volume: 1, pitch: 1, photoSec: 3 };
    if (image) {
      const img = await new Promise((res) => { const i = new Image(); i.onload = () => res(i); i.onerror = () => res(null); i.src = url; });
      c.width = img ? img.naturalWidth : 1080; c.height = img ? img.naturalHeight : 1920; c.thumb = url; c.hasAudio = false;
    } else {
      const t = await api.thumb(p);
      Object.assign(c, { width: t.width || 1280, height: t.height || 720, hasAudio: t.hasAudio, thumb: t.thumb,
        sourceSec: t.duration || 0, start: 0, end: t.duration || 0 });
      if (!t.duration) toast('Couldn\'t read ' + c.name);
    }
    const at = VE.sel < 0 ? VE.clips.length : VE.sel + 1;
    VE.clips.splice(at, 0, c);
    VE.sel = at;
  }
  prog.close();
  if (NAV.view === 'veditor') refreshView();
}

async function pickClips() {
  const picked = await api.open({ title: 'Add videos or photos', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Videos and photos', extensions: ['mp4', 'mkv', 'webm', 'mov', 'avi', 'm4v', '3gp', 'wmv', 'flv', 'jpg', 'jpeg', 'png', 'webp', 'bmp'] }] });
  if (picked.length) addClips(picked.map((f) => f.path));
}

function videoEditorView(arg) {
  if (arg && arg.files) { NAV.arg = null; addClips(arg.files); }
  main.appendChild(head('Video editor',
    el('button.btn', { onclick: async () => { if (VE.clips.length && !(await confirmBox('New project', 'Start again with an empty project?', 'Start again', true))) return; Object.assign(VE, newProject()); refreshView(); } }, 'New project'),
    el('button.btn', { onclick: pickClips }, '+ Add clips'),
    el('button.btn', { onclick: () => exportVideo(true) }, 'Extract audio (MP3)'),
    el('button.btn.primary', { onclick: () => exportVideo(false) }, 'Export video')));
  const c = curClip();
  const stage = el('div.stage');
  const r = ratioNum();
  stage.style.aspectRatio = 'auto';
  stage.style.margin = '0 auto';
  // size the preview to the video's shape, as big as fits
  requestAnimationFrame(() => {
    const maxW = stage.parentElement ? stage.parentElement.clientWidth : 800, maxH = innerHeight * 0.52;
    let w = maxW, h = w / r;
    if (h > maxH) { h = maxH; w = h * r; }
    stage.style.width = Math.round(w) + 'px';
    stage.style.height = Math.round(h) + 'px';
    drawOverlay(overlay, Math.round(w), Math.round(h));
  });
  if (!VE.clips.length) stage.appendChild(el('div.hint', 'Click "+ Add clips" to choose videos or photos'));
  let media = null;
  if (c) {
    media = c.image ? el('img', { src: c.url }) : el('video#vePreview', { src: c.url, preload: 'auto' });
    media.style.width = '100%'; media.style.height = '100%';
    media.style.objectFit = VE.aspectFill ? 'cover' : 'contain';
    media.style.filter = cssFilter();
    media.style.transform = `rotate(${VE.rotate}deg) scale(${VE.flipH ? -1 : 1}, ${VE.flipV ? -1 : 1})` + (VE.rotate % 180 ? ` scale(${Math.min(r, 1 / r)})` : '');
    stage.appendChild(media);
  }
  const overlay = el('canvas');
  stage.appendChild(overlay);
  const left = el('div', stage);
  const timeTxt = el('span.sub', '');
  if (c && !c.image) {
    const v = media;
    v.addEventListener('loadedmetadata', () => { v.currentTime = c.start; v.playbackRate = c.speed; v.preservesPitch = c.pitch === 1; v.volume = Math.min(1, c.volume * VE.originalVolume); });
    v.addEventListener('timeupdate', () => {
      if (v.currentTime >= c.end) { v.pause(); v.currentTime = c.start; }
      timeTxt.textContent = fmtTime((v.currentTime - c.start) * 1000) + ' / ' + fmtTime((c.end - c.start) * 1000);
    });
    const playB = el('button.play', { onclick: () => { if (v.paused) v.play(); else v.pause(); } }, icon('play'));
    v.addEventListener('play', () => playB.firstChild.firstChild.setAttribute('href', '#i-pause'));
    v.addEventListener('pause', () => playB.firstChild.firstChild.setAttribute('href', '#i-play'));
    left.appendChild(el('div.row', { style: { marginTop: '10px' } }, playB, timeTxt, el('div.grow'),
      el('span.dim', plural(VE.clips.length, 'clip') + ' · ' + fmtTime(VE.clips.reduce((t, x) => t + clipLen(x), 0) * 1000))));
  }
  // timeline
  const tl = el('div.timeline');
  VE.clips.forEach((x, i) => tl.appendChild(el('div.clip' + (i === VE.sel ? '.on' : ''), {
    style: { background: x.thumb ? `center/cover url("${x.thumb}")` : grad(x.name) },
    onclick: () => { VE.sel = i; refreshView(); }, title: x.name,
  }, el('div.badge', fmtTime(clipLen(x) * 1000) + (x.speed !== 1 ? '  ' + x.speed + 'x' : '')))));
  tl.appendChild(el('div.clip.add', { onclick: pickClips }, '+ Add'));
  left.appendChild(tl);
  // trim
  if (c && !c.image && c.sourceSec) {
    const s = el('input', { type: 'range', min: 0, max: c.sourceSec, step: 0.05, value: c.start });
    const e = el('input', { type: 'range', min: 0, max: c.sourceSec, step: 0.05, value: c.end });
    const sl = el('span', fmtTime(c.start * 1000)), elb = el('span', fmtTime(c.end * 1000));
    s.oninput = () => { c.start = Math.min(+s.value, c.end - 0.3); s.value = c.start; sl.textContent = fmtTime(c.start * 1000); if (media) media.currentTime = c.start; };
    e.oninput = () => { c.end = Math.max(+e.value, c.start + 0.3); e.value = c.end; elb.textContent = fmtTime(c.end * 1000); if (media) media.currentTime = Math.max(c.start, c.end - 1.5); };
    s.onchange = e.onchange = () => refreshView();
    left.appendChild(el('div.trim.glass', { style: { padding: '12px 16px', marginTop: '6px' } },
      el('div.label', el('span', 'Start'), sl), s, el('div.label', el('span', 'End'), elb), e,
      el('div.dim', { style: { fontSize: '12px', marginTop: '6px' } }, `Using ${fmtTime((c.end - c.start) * 1000)} of ${fmtTime(c.sourceSec * 1000)}`)));
  }
  // tools
  const tool = (ic, name, fn) => el('div.tool', { onclick: fn }, icon(ic), name);
  const tools = el('div.tools',
    el('b', 'Clip'),
    el('div.grid',
      tool('cut', 'Split here', splitClip), tool('close', 'Delete', delClip), tool('add', 'Duplicate', dupClip),
      tool('back', 'Move left', () => moveClip(-1)), tool('next', 'Move right', () => moveClip(1)), tool('speed', 'Speed', speedClip),
      tool('vol', 'Volume', volumeClip), tool('music', 'Voice', voiceClip), tool('image', 'Photo time', photoTime),
      tool('image', 'Save frame', saveFrame)),
    el('b', 'Video'),
    el('div.grid',
      tool('filter', 'Filters', pickFilter), tool('filter', 'Adjust', adjustDlg), tool('aspect', 'Canvas', canvasDlg),
      tool('rotate', 'Rotate', () => { VE.rotate = (VE.rotate + 90) % 360; refreshView(); }), tool('aspect', 'Flip', flipDlg),
      tool('text', 'Text', textTool), tool('image', 'Sticker', stickerTool), tool('music', 'Music', musicTool)),
    el('div.glass', { style: { padding: '12px 14px', fontSize: '12px', lineHeight: '1.6' } },
      el('div', 'Output: ', el('b', outLabel())), el('div.dim', 'Filter: ' + VE.filter + (VE.music ? ' · Music: ' + VE.music.name : '') +
        (VE.texts.length ? ' · ' + plural(VE.texts.length, 'text') : '') + (VE.sticker ? ' · sticker' : ''))));
  main.appendChild(el('div.editor', left, tools));
}

function ratioNum() {
  const c = VE.clips[0];
  let w = c ? c.width : 16, h = c ? c.height : 9;
  if (VE.rotate % 180) [w, h] = [h, w];
  return VE.aspect > 0 ? VE.aspect : w / h;
}
function outRatio() { return String(ratioNum()); }
function outSizeLocal() {
  const c = VE.clips[0] || { width: 1280, height: 720 };
  let w = c.width, h = c.height;
  if (VE.rotate % 180) [w, h] = [h, w];
  if (VE.aspect > 0) {
    const cur = w / h;
    if (VE.aspectFill) { if (cur > VE.aspect) w = Math.round(h * VE.aspect); else h = Math.round(w / VE.aspect); }
    else if (cur > VE.aspect) h = Math.round(w / VE.aspect); else w = Math.round(h * VE.aspect);
  }
  let scale = 1;
  if (VE.quality > 0 && Math.min(w, h) > VE.quality) scale = VE.quality / Math.min(w, h);
  if (Math.max(w, h) * scale > 3840) scale = 3840 / Math.max(w, h);
  return [Math.max(2, Math.round(w * scale / 2) * 2), Math.max(2, Math.round(h * scale / 2) * 2)];
}
function outLabel() { const [w, h] = outSizeLocal(); return `${w}×${h}` + (VE.quality ? ` (${VE.quality}p)` : ' (original)'); }
function cssFilter() {
  const f = [CSS_FILTER[VE.filter] || ''];
  if (VE.brightness) f.push(`brightness(${1 + VE.brightness / 200})`);
  if (VE.contrast) f.push(`contrast(${1 + VE.contrast / 100})`);
  if (VE.saturation) f.push(`saturate(${1 + VE.saturation / 100})`);
  return f.join(' ').trim() || 'none';
}

/** Texts and the sticker, drawn at any size (the preview, or the real video size for export). */
function drawOverlay(canvas, w, h) {
  canvas.width = w; canvas.height = h;
  const g = canvas.getContext('2d');
  g.clearRect(0, 0, w, h);
  return new Promise((resolve) => {
    const finish = () => {
      for (const t of VE.texts) {
        if (!t.text.trim()) continue;
        let size = t.size * h;
        const font = (sz) => `${t.bold ? '700' : '400'} ${sz}px "Segoe UI", "Noto Naskh Arabic", "Noto Sans", sans-serif`;
        g.font = font(size);
        g.textAlign = 'center'; g.textBaseline = 'middle';
        const lines = t.text.split('\n');
        const widest = Math.max(...lines.map((l) => g.measureText(l).width));
        if (widest > w * 0.92) { size *= w * 0.92 / widest; g.font = font(size); } // shrink to fit the video's width
        const lh = size * 1.25;
        const top = t.y * h - lh * (lines.length - 1) / 2;
        if (t.box) {
          const mw = Math.max(...lines.map((l) => g.measureText(l).width));
          const pad = size * 0.35;
          g.fillStyle = t.color === '#000000' ? 'rgba(255,255,255,.85)' : 'rgba(0,0,0,.7)';
          roundRect(g, t.x * w - mw / 2 - pad, top - lh / 2 - pad * 0.4, mw + pad * 2, lh * lines.length + pad * 0.8, pad);
        }
        g.fillStyle = t.color;
        g.shadowColor = t.box ? 'transparent' : 'rgba(0,0,0,.8)';
        g.shadowBlur = t.box ? 0 : size * 0.15;
        g.shadowOffsetY = t.box ? 0 : size * 0.04;
        lines.forEach((l, k) => g.fillText(l, t.x * w, top + k * lh));
        g.shadowColor = 'transparent';
      }
      resolve(canvas);
    };
    if (VE.sticker) {
      const img = new Image();
      img.onload = () => {
        const sw = VE.sticker.size * w, sh = sw * img.naturalHeight / img.naturalWidth;
        g.drawImage(img, VE.sticker.x * w - sw / 2, VE.sticker.y * h - sh / 2, sw, sh);
        finish();
      };
      img.onerror = finish;
      img.src = VE.sticker.url;
    } else finish();
  });
}
function roundRect(g, x, y, w, h, r) {
  g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); g.fill();
}

// ---------- clip tools ----------
function need() { const c = curClip(); if (!c) toast('Add a clip first'); return c; }
function splitClip() {
  const c = need(); if (!c) return;
  if (c.image) { toast('Photos can\'t be split. Use Photo time instead.'); return; }
  const v = $('#vePreview');
  const at = v ? v.currentTime : 0;
  if (at <= c.start + 0.3 || at >= c.end - 0.3) { toast('Play to where you want to split, pause, then click Split here'); return; }
  const b = { ...c, start: at };
  c.end = at;
  VE.clips.splice(VE.sel + 1, 0, b);
  toast('Split at ' + fmtTime(at * 1000));
  refreshView();
}
function delClip() { if (!need()) return; VE.clips.splice(VE.sel, 1); VE.sel = Math.min(VE.sel, VE.clips.length - 1); refreshView(); }
function dupClip() { const c = need(); if (!c) return; VE.clips.splice(VE.sel + 1, 0, { ...c }); VE.sel++; refreshView(); }
function moveClip(d) {
  const c = need(); if (!c) return;
  const j = VE.sel + d;
  if (j < 0 || j >= VE.clips.length) return;
  VE.clips.splice(VE.sel, 1); VE.clips.splice(j, 0, c); VE.sel = j; refreshView();
}
async function speedClip() {
  const c = need(); if (!c) return;
  if (c.image) { toast('For photos, use Photo time'); return; }
  const v = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4];
  const i = await choose('Clip speed', v.map((x) => x === 1 ? 'Normal (1x)' : x + 'x' + (x < 1 ? '  — slow motion' : '  — fast')));
  if (i >= 0) { c.speed = v[i]; refreshView(); }
}
async function volumeClip() {
  const c = need(); if (!c) return;
  const box = el('div');
  const r = sliderRow(box, 'Volume of this clip', 0, 200, Math.round(c.volume * 100), '%');
  if (await dialog({ title: 'Clip volume', body: box, buttons: [{ text: 'Cancel', value: false }, { text: 'OK', primary: true, value: true }] })) { c.volume = r.value / 100; refreshView(); }
}
async function voiceClip() {
  const c = need(); if (!c) return;
  const p = [1, 1.5, 1.25, 0.8, 0.6], n = ['Normal', 'Chipmunk', 'Higher', 'Deeper', 'Monster'];
  const i = await choose('Voice effect', n);
  if (i >= 0) { c.pitch = p[i]; toast('Voice: ' + n[i]); refreshView(); }
}
async function photoTime() {
  const c = need(); if (!c) return;
  if (!c.image) { toast('This is for photos. Use the Start and End sliders to trim a video.'); return; }
  const box = el('div');
  const r = sliderRow(box, 'Show this photo for', 1, 20, c.photoSec, ' s');
  if (await dialog({ title: 'Photo time', body: box, buttons: [{ text: 'Cancel', value: false }, { text: 'OK', primary: true, value: true }] })) { c.photoSec = +r.value; refreshView(); }
}
async function saveFrame() {
  const c = need(); if (!c) return;
  const v = $('#vePreview');
  const at = c.image ? 0 : (v ? v.currentTime : c.start);
  const info = await api.info();
  const out = await api.saveDialog({ title: 'Save frame as a picture', defaultPath: info.downloads + '\\Echo frame.png', filters: [{ name: 'PNG picture', extensions: ['png'] }] });
  if (!out) return;
  try { await api.frame(c.path, at, out); toast('Picture saved'); api.showInFolder(out); } catch (e) { alertBox('Couldn\'t save', String(e.message || e)); }
}

// ---------- video tools ----------
async function pickFilter() { const i = await choose('Filter', FILTERS); if (i >= 0) { VE.filter = FILTERS[i]; refreshView(); } }
async function adjustDlg() {
  const box = el('div');
  const b = sliderRow(box, 'Brightness', -100, 100, VE.brightness), c = sliderRow(box, 'Contrast', -100, 100, VE.contrast),
    s = sliderRow(box, 'Saturation', -100, 100, VE.saturation);
  const v = await dialog({ title: 'Adjust', body: box, buttons: [{ text: 'Reset', value: 'reset' }, { text: 'Cancel', value: false }, { text: 'Apply', primary: true, value: true }] });
  if (v === 'reset') { VE.brightness = VE.contrast = VE.saturation = 0; refreshView(); }
  else if (v) { VE.brightness = +b.value; VE.contrast = +c.value; VE.saturation = +s.value; refreshView(); }
}
async function canvasDlg() {
  const ratios = [0, 9 / 16, 16 / 9, 1, 4 / 5, 4 / 3, 3 / 4];
  const i = await choose('Canvas size', ['Original', '9:16  TikTok · Reels · Shorts', '16:9  YouTube', '1:1  Square', '4:5  Instagram post', '4:3', '3:4']);
  if (i < 0) return;
  VE.aspect = ratios[i];
  if (i > 0) { const j = await choose('Fit the video', ['Fill (crop the edges)', 'Fit (add black bars)']); VE.aspectFill = j !== 1; }
  refreshView();
}
async function flipDlg() {
  const i = await choose('Flip', ['Mirror (left ↔ right)', 'Upside down', 'No flip']);
  if (i === 0) VE.flipH = !VE.flipH; else if (i === 1) VE.flipV = !VE.flipV; else if (i === 2) VE.flipH = VE.flipV = false;
  refreshView();
}

async function textTool() {
  if (!VE.clips.length) { toast('Add a clip first'); return; }
  const i = await choose('Text on the video', ['+ Add text', ...VE.texts.map((t) => '✎  ' + t.text.replace(/\n/g, ' '))]);
  if (i === 0) editText(null); else if (i > 0) editText(VE.texts[i - 1]);
}
async function editText(existing) {
  const t = existing || { text: '', color: '#ffffff', size: 0.07, x: 0.5, y: 0.85, box: false, bold: true };
  const box = el('div');
  const ta = el('textarea', { rows: 3, placeholder: 'Your text (Urdu, Pashto and Arabic work too)', style: { width: '100%' } });
  ta.value = t.text;
  box.appendChild(ta);
  const colors = ['#ffffff', '#000000', '#ffd60a', '#7df9ff', '#ff4d9d', '#ff3b30', '#34c759'];
  let color = t.color;
  const sw = el('div.swatches');
  colors.forEach((c) => sw.appendChild(el('span' + (c === color ? '.on' : ''), { style: { background: c }, onclick: (e) => { color = c; $$('span', sw).forEach((x) => x.classList.remove('on')); e.target.classList.add('on'); } })));
  box.appendChild(el('div.label', 'Colour'));
  box.appendChild(sw);
  const size = sliderRow(box, 'Size', 2, 20, Math.round(t.size * 100));
  const y = sliderRow(box, 'Position (top ↔ bottom)', 4, 96, Math.round(t.y * 100), '%');
  const x = sliderRow(box, 'Position (left ↔ right)', 4, 96, Math.round(t.x * 100), '%');
  const bx = el('input', { type: 'checkbox' }); bx.checked = t.box;
  box.appendChild(el('label.label', { style: { justifyContent: 'flex-start', gap: '8px' } }, bx, 'Box behind the text'));
  const btns = [{ text: 'Cancel', value: false }, { text: 'Done', primary: true, value: true }];
  if (existing) btns.unshift({ text: 'Delete', danger: true, value: 'delete' });
  const v = await dialog({ title: existing ? 'Edit text' : 'Add text', body: box, buttons: btns });
  if (v === 'delete') VE.texts.splice(VE.texts.indexOf(existing), 1);
  else if (v) {
    Object.assign(t, { text: ta.value, color, size: size.value / 100, y: y.value / 100, x: x.value / 100, box: bx.checked });
    if (!existing && t.text.trim()) VE.texts.push(t);
  }
  refreshView();
}

async function stickerTool() {
  if (!VE.sticker) {
    const picked = await api.open({ title: 'Choose a picture or sticker (PNG with a clear background works best)', properties: ['openFile'],
      filters: [{ name: 'Pictures', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'] }] });
    if (!picked.length) return;
    VE.sticker = { path: picked[0].path, url: picked[0].url, x: 0.82, y: 0.15, size: 0.22 };
    refreshView();
    return;
  }
  const box = el('div');
  const s = sliderRow(box, 'Size', 5, 100, Math.round(VE.sticker.size * 100), '%');
  const x = sliderRow(box, 'Left ↔ right', 0, 100, Math.round(VE.sticker.x * 100), '%');
  const y = sliderRow(box, 'Top ↔ bottom', 0, 100, Math.round(VE.sticker.y * 100), '%');
  const v = await dialog({ title: 'Sticker', body: box, buttons: [{ text: 'Remove', danger: true, value: 'remove' }, { text: 'Change picture', value: 'change' }, { text: 'Done', primary: true, value: true }] });
  if (v === 'remove') VE.sticker = null;
  else if (v === 'change') { VE.sticker = null; return stickerTool(); }
  else if (v) Object.assign(VE.sticker, { size: s.value / 100, x: x.value / 100, y: y.value / 100 });
  refreshView();
}

async function musicTool() {
  if (!VE.music) {
    const picked = await api.open({ title: 'Choose background music', properties: ['openFile'], filters: [{ name: 'Audio', extensions: ['mp3', 'm4a', 'wav', 'aac', 'flac', 'ogg', 'opus'] }] });
    if (!picked.length) return;
    VE.music = { path: picked[0].path, name: picked[0].name };
  }
  const box = el('div', el('b', '♪ ' + VE.music.name));
  const mv = sliderRow(box, 'Music volume', 0, 150, Math.round(VE.musicVolume * 100), '%');
  const ov = sliderRow(box, 'Original sound', 0, 150, Math.round(VE.originalVolume * 100), '%');
  const v = await dialog({ title: 'Background music', body: box, buttons: [{ text: 'Remove music', danger: true, value: 'remove' }, { text: 'Change song', value: 'change' }, { text: 'Done', primary: true, value: true }] });
  if (v === 'remove') VE.music = null;
  else if (v === 'change') { VE.music = null; return musicTool(); }
  else if (v) { VE.musicVolume = mv.value / 100; VE.originalVolume = ov.value / 100; }
  refreshView();
}

// ---------- export ----------
async function exportVideo(audioOnly) {
  if (!VE.clips.length) { toast('Add a clip first'); return; }
  if (audioOnly && VE.clips.every((c) => c.image)) { toast('There\'s no sound to extract: all clips are photos'); return; }
  if (!audioOnly) {
    const q = [480, 720, 1080, 0];
    const i = await choose('Export quality', ['480p — small file', '720p HD', '1080p Full HD (recommended)', 'Original quality']);
    if (i < 0) return;
    VE.quality = q[i];
  }
  const info = await api.info();
  const base = VE.clips[0].name.replace(/\.[^.]+$/, '') + ' (Echo)';
  const out = await api.saveDialog({
    title: audioOnly ? 'Save the audio' : 'Save the video',
    defaultPath: (audioOnly ? info.music : info.videos) + '\\' + base + (audioOnly ? '.mp3' : '.mp4'),
    filters: audioOnly ? [{ name: 'MP3 audio', extensions: ['mp3'] }] : [{ name: 'MP4 video', extensions: ['mp4'] }],
  });
  if (!out) return;
  const [w, h] = outSizeLocal();
  let overlay = null;
  if (!audioOnly && (VE.texts.length || VE.sticker)) {
    const cv = await drawOverlay(document.createElement('canvas'), w, h);
    overlay = await api.writeTemp('overlay.png', cv.toDataURL('image/png'));
  }
  const project = {
    clips: VE.clips.map((c) => ({ path: c.path, image: c.image, start: c.start || 0, end: c.end || 0, photoSec: c.photoSec,
      speed: c.speed, volume: c.volume, pitch: c.pitch, hasAudio: c.hasAudio, width: c.width, height: c.height })),
    rotate: VE.rotate, flipH: VE.flipH, flipV: VE.flipV, filter: VE.filter, brightness: VE.brightness, contrast: VE.contrast,
    saturation: VE.saturation, aspect: VE.aspect, aspectFill: VE.aspectFill, quality: VE.quality, overlay,
    music: VE.music && VE.music.path, musicVolume: VE.musicVolume, originalVolume: VE.originalVolume,
  };
  const job = 'v' + Date.now();
  const prog = progressBox(audioOnly ? 'Extracting audio' : 'Exporting video', () => api.ffCancel(job));
  api.onFfProgress((id, pct) => { if (id === job) prog.set(pct, pct + '%  ·  you can keep using Echo'); });
  const r = await api.ffExport({ id: job, kind: audioOnly ? 'extract' : 'video', project, out });
  prog.close();
  if (!r.ok) { if (!r.cancelled) alertBox('Export didn\'t work', r.error); return; }
  VIDS = null;
  if (audioOnly) {
    const s = { id: 'f:' + out.toLowerCase(), source: 'file', path: out, uri: r.url, title: base, artist: 'Extracted audio', album: '', duration: 0, added: Date.now() };
    addSong(s); save();
  }
  const v = await dialog({ title: audioOnly ? 'Audio saved' : 'Video saved',
    body: esc(out) + (audioOnly ? '<br><br>It\'s also in your Echo songs.' : ''),
    buttons: [{ text: 'Show in folder', value: 'show' }, { text: 'Keep editing', value: false }, { text: 'Play', primary: true, value: 'play' }] });
  if (v === 'show') api.showInFolder(out);
  else if (v === 'play') {
    if (audioOnly) playList([song('f:' + out.toLowerCase())], 0);
    else openVideo([{ path: out, url: r.url, name: base }], 0);
  }
}
