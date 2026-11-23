// Photo editor: crop (with fixed shapes), turn, flip, straighten, light and colour, filters, text and drawing.
// The preview uses a smaller copy so it reacts instantly; saving works on the full-size photo.
'use strict';

const PE_FILTERS = [
  ['none', 'Original', ''], ['vivid', 'Vivid', 'saturate(1.45) contrast(1.1)'], ['warm', 'Warm', ''], ['cool', 'Cool', ''],
  ['bw', 'Black & white', 'grayscale(1) contrast(1.1)'], ['noir', 'Noir', 'grayscale(1) contrast(1.45) brightness(0.92)'],
  ['sepia', 'Sepia', 'sepia(0.85)'], ['vintage', 'Vintage', 'sepia(0.35) contrast(0.9) saturate(0.85)'],
  ['fade', 'Faded', 'contrast(0.85) brightness(1.07) saturate(0.8)'], ['drama', 'Drama', 'contrast(1.35) saturate(1.15)'],
  ['pastel', 'Pastel', 'saturate(0.7) brightness(1.1)'], ['punch', 'Punch', 'saturate(1.7) contrast(1.2)'],
];
const PE_EXTRA = { warm: { warmth: 35 }, cool: { warmth: -35 }, vintage: { fade: 30, vignette: 25 }, fade: { fade: 25 }, drama: { vignette: 45 } };
const PE_ADJ = [
  ['bright', 'Brightness', -100, 100], ['exposure', 'Exposure', -100, 100], ['contrast', 'Contrast', -100, 100], ['sat', 'Saturation', -100, 100],
  ['warmth', 'Warmth', -100, 100], ['tint', 'Tint (green – pink)', -100, 100], ['fade', 'Fade', 0, 100], ['vignette', 'Vignette', 0, 100],
  ['sharpen', 'Sharpen', 0, 100], ['blur', 'Blur', 0, 100],
];
const PE_ASPECTS = [['free', 'Free'], ['orig', 'Original'], ['1:1', 'Square'], ['4:5', '4:5'], ['5:4', '5:4'], ['3:4', '3:4'], ['4:3', '4:3'],
  ['2:3', '2:3'], ['3:2', '3:2'], ['9:16', '9:16'], ['16:9', '16:9']];

const peFresh = () => ({ rot: 0, flipH: false, flipV: false, straight: 0, crop: { x: 0, y: 0, w: 1, h: 1 }, aspect: 'free',
  adj: Object.fromEntries(PE_ADJ.map((a) => [a[0], 0])), filter: 'none', texts: [], strokes: [] });
let PE = null;

function photoEditorView(arg) {
  const path = arg && arg.path;
  if (!path && !PE) {
    main.appendChild(head('Photo editor'));
    main.appendChild(el('div.empty', el('p', 'Choose a photo to edit. You can also open one from Photos and press ✏ Edit.'),
      el('button.btn.primary', { onclick: async () => {
        const r = await api.openDialog({ title: 'Choose a photo', properties: ['openFile'], filters: [{ name: 'Photos', extensions: ['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'avif', 'jfif'] }] });
        if (r && r[0]) go('peditor', { path: r[0] });
      } }, 'Choose a photo')));
    return;
  }
  if (path && (!PE || PE.path !== path)) { peOpen(path); return; }
  peBuild();
}

async function peOpen(path) {
  main.appendChild(el('div.empty', 'Opening the photo…'));
  const url = await api.fileUrl(path);
  const img = new Image();
  img.onload = () => {
    // a smaller copy for the live preview
    const k = Math.min(1, 1800 / Math.max(img.naturalWidth, img.naturalHeight));
    const small = document.createElement('canvas');
    small.width = Math.round(img.naturalWidth * k); small.height = Math.round(img.naturalHeight * k);
    small.getContext('2d').drawImage(img, 0, 0, small.width, small.height);
    PE = { path, url, img, small, name: path.split(/[\\/]/).pop().replace(/\.[^.]+$/, ''), s: peFresh(), hist: [], hi: -1, tab: 'crop', selText: -1, dirty: false };
    pePush();
    if (NAV.view === 'peditor') { main.innerHTML = ''; peBuild(); }
  };
  img.onerror = () => { main.innerHTML = ''; main.appendChild(el('div.empty', 'This photo couldn\'t be opened.')); };
  img.src = url;
}

// ---------- history ----------
function pePush() {
  PE.hist = PE.hist.slice(0, PE.hi + 1);
  PE.hist.push(JSON.stringify(PE.s));
  if (PE.hist.length > 60) PE.hist.shift();
  PE.hi = PE.hist.length - 1;
  PE.dirty = PE.hi > 0;
}
function peUndo() { if (PE.hi > 0) { PE.hi--; PE.s = JSON.parse(PE.hist[PE.hi]); peRefreshAll(); } }
function peRedo() { if (PE.hi < PE.hist.length - 1) { PE.hi++; PE.s = JSON.parse(PE.hist[PE.hi]); peRefreshAll(); } }

// ---------- drawing the picture ----------
/** The photo turned, flipped and straightened (straightening zooms in just enough to hide the corners). */
function peOriented(src, s) {
  const sw = src.width || src.naturalWidth, sh = src.height || src.naturalHeight;
  const q = ((s.rot % 360) + 360) % 360;
  const W = q % 180 ? sh : sw, H = q % 180 ? sw : sh;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const x = c.getContext('2d');
  x.imageSmoothingQuality = 'high';
  x.translate(W / 2, H / 2);
  const a = (s.straight || 0) * Math.PI / 180;
  if (a) {
    const ca = Math.abs(Math.cos(a)), sa = Math.abs(Math.sin(a));
    const k = Math.max((W * ca + H * sa) / W, (W * sa + H * ca) / H);
    x.rotate(a); x.scale(k, k);
  }
  x.rotate(q * Math.PI / 180);
  x.scale(s.flipH ? -1 : 1, s.flipV ? -1 : 1);
  x.drawImage(src, -sw / 2, -sh / 2, sw, sh);
  return c;
}

function peCssFilter(s) {
  const a = s.adj;
  const f = PE_FILTERS.find((x) => x[0] === s.filter);
  const b = 1 + a.bright / 200 + a.exposure / 120;
  return [`brightness(${b.toFixed(3)})`, `contrast(${(1 + a.contrast / 100).toFixed(3)})`, `saturate(${(1 + a.sat / 100).toFixed(3)})`,
    a.blur ? `blur(${(a.blur / 100 * 0.012).toFixed(4)}PX)` : '', f ? f[2] : ''].filter(Boolean).join(' ');
}

/** Draws the finished picture (crop, colours, text, drawing) into a new canvas of the given width. */
function peRender(src, s, outW) {
  const o = peOriented(src, s);
  const c = s.crop;
  const cx = c.x * o.width, cy = c.y * o.height, cw = Math.max(1, c.w * o.width), ch = Math.max(1, c.h * o.height);
  const W = Math.max(1, Math.round(outW || cw)), H = Math.max(1, Math.round(W * ch / cw));
  const out = document.createElement('canvas');
  out.width = W; out.height = H;
  const x = out.getContext('2d');
  x.imageSmoothingQuality = 'high';
  const blurPx = s.adj.blur ? s.adj.blur / 100 * 0.012 * W : 0;
  x.filter = peCssFilter(s).replace(/blur\([^)]*\)/, blurPx ? `blur(${blurPx.toFixed(2)}px)` : '');
  x.drawImage(o, cx, cy, cw, ch, 0, 0, W, H);
  x.filter = 'none';
  const extra = PE_EXTRA[s.filter] || {};
  const warmth = s.adj.warmth + (extra.warmth || 0), tint = s.adj.tint, fade = s.adj.fade + (extra.fade || 0), vig = s.adj.vignette + (extra.vignette || 0);
  const wash = (color, alpha, mode) => { x.save(); x.globalCompositeOperation = mode; x.globalAlpha = Math.min(1, alpha); x.fillStyle = color; x.fillRect(0, 0, W, H); x.restore(); };
  if (warmth) wash(warmth > 0 ? '#ff8a2a' : '#2a7dff', Math.abs(warmth) / 100 * 0.45, 'soft-light');
  if (tint) wash(tint > 0 ? '#ff3cc8' : '#3cff6e', Math.abs(tint) / 100 * 0.35, 'soft-light');
  if (fade) wash('#8a8a8a', fade / 100 * 0.45, 'lighten');
  if (vig) {
    const g = x.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.25, W / 2, H / 2, Math.hypot(W, H) / 2);
    g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, `rgba(0,0,0,${(vig / 100 * 0.85).toFixed(3)})`);
    x.fillStyle = g; x.fillRect(0, 0, W, H);
  }
  if (s.adj.sharpen) peSharpen(x, W, H, s.adj.sharpen / 100);
  // drawing
  for (const st of s.strokes) {
    if (!st.pts.length) continue;
    x.save();
    x.strokeStyle = st.color; x.lineWidth = Math.max(1, st.size * Math.min(W, H)); x.lineCap = 'round'; x.lineJoin = 'round';
    x.globalAlpha = st.alpha || 1;
    x.beginPath();
    x.moveTo(st.pts[0][0] * W, st.pts[0][1] * H);
    for (const p of st.pts.slice(1)) x.lineTo(p[0] * W, p[1] * H);
    if (st.pts.length === 1) x.lineTo(st.pts[0][0] * W + 0.1, st.pts[0][1] * H);
    x.stroke();
    x.restore();
  }
  // text
  for (const t of s.texts) peDrawText(x, t, W, H);
  return out;
}

function peTextFont(t, H) { return `${t.bold ? '800' : '500'} ${Math.max(6, t.size * H).toFixed(1)}px ${t.font || 'Segoe UI'}`; }
function peDrawText(x, t, W, H) {
  x.save();
  x.font = peTextFont(t, H);
  x.textAlign = 'center'; x.textBaseline = 'middle';
  const lines = String(t.text).split('\n');
  const lh = t.size * H * 1.15;
  const y0 = t.y * H - (lines.length - 1) * lh / 2;
  if (t.bg) {
    const w = Math.max(...lines.map((l) => x.measureText(l).width)) + t.size * H * 0.6;
    x.fillStyle = t.bg; x.globalAlpha = 0.75;
    x.fillRect(t.x * W - w / 2, y0 - lh / 2 - t.size * H * 0.15, w, lh * lines.length + t.size * H * 0.3);
    x.globalAlpha = 1;
  }
  if (t.shadow) { x.shadowColor = 'rgba(0,0,0,.7)'; x.shadowBlur = t.size * H * 0.18; x.shadowOffsetY = t.size * H * 0.05; }
  if (t.outline) { x.lineWidth = Math.max(1, t.size * H * 0.08); x.strokeStyle = t.outline; lines.forEach((l, i) => x.strokeText(l, t.x * W, y0 + i * lh)); }
  x.fillStyle = t.color;
  lines.forEach((l, i) => x.fillText(l, t.x * W, y0 + i * lh));
  x.restore();
}
/** The text's box (in 0–1 of the picture), for picking it with the mouse. */
function peTextBox(t, W, H) {
  const c = document.createElement('canvas').getContext('2d');
  c.font = peTextFont(t, H);
  const lines = String(t.text).split('\n');
  const w = Math.max(...lines.map((l) => c.measureText(l).width)) / W;
  const h = t.size * 1.15 * lines.length;
  return { x: t.x - w / 2 - 0.01, y: t.y - h / 2 - 0.01, w: w + 0.02, h: h + 0.02 };
}

function peSharpen(x, W, H, amt) {
  const d = x.getImageData(0, 0, W, H);
  const s = d.data, o = new Uint8ClampedArray(s.length);
  const a = amt * 0.9, c = 1 + 4 * a;
  for (let yy = 0; yy < H; yy++) {
    for (let xx = 0; xx < W; xx++) {
      const i = (yy * W + xx) * 4;
      const up = yy ? i - W * 4 : i, dn = yy < H - 1 ? i + W * 4 : i, lf = xx ? i - 4 : i, rt = xx < W - 1 ? i + 4 : i;
      for (let ch = 0; ch < 3; ch++) o[i + ch] = c * s[i + ch] - a * (s[up + ch] + s[dn + ch] + s[lf + ch] + s[rt + ch]);
      o[i + 3] = s[i + 3];
    }
  }
  d.data.set(o);
  x.putImageData(d, 0, 0);
}

// ---------- the screen ----------
function peBuild() {
  const stage = el('div.pestage');
  const canvas = el('canvas.pecanvas');
  const overlay = el('div.peover');
  stage.append(canvas, overlay);
  const panel = el('div.pepanel');
  const tabs = el('div.petabs');
  const tabList = [['crop', '✂ Crop & turn'], ['adjust', '☀ Adjust'], ['filters', '🎨 Filters'], ['text', 'T Text'], ['draw', '✎ Draw']];
  const tabBody = el('div.pebody');
  const drawTabs = () => {
    tabs.innerHTML = '';
    for (const [k, t] of tabList) tabs.appendChild(el('div.chip' + (PE.tab === k ? '.on' : ''), { onclick: () => { PE.tab = k; PE.selText = -1; drawTabs(); peFillTab(tabBody); peRefresh(); } }, t));
  };
  panel.append(tabs, tabBody);
  const compare = el('button.btn', { title: 'Hold to see the original' }, 'Compare');
  compare.addEventListener('pointerdown', () => { PE.compare = true; peRefresh(); });
  ['pointerup', 'pointerleave'].forEach((ev) => compare.addEventListener(ev, () => { if (PE.compare) { PE.compare = false; peRefresh(); } }));
  const top = el('div.pebar',
    iconBtn('back', 'Back', () => goBack()), el('div.pvtt', el('div.pvtitle', PE.name), el('div.dim', `${PE.img.naturalWidth} × ${PE.img.naturalHeight}`)),
    el('button.btn', { title: 'Undo (Ctrl+Z)', onclick: peUndo }, '↶ Undo'), el('button.btn', { title: 'Redo (Ctrl+Y)', onclick: peRedo }, '↷ Redo'),
    compare,
    el('button.btn', { onclick: async () => { if (await confirmBox('Start over?', 'All your changes to this photo are undone.', 'Start over')) { PE.s = peFresh(); pePush(); peRefreshAll(); } } }, 'Reset'),
    el('button.btn', { onclick: () => peSave(false) }, 'Save a copy'),
    el('button.btn.primary', { onclick: () => peSave(true) }, 'Save'));
  main.appendChild(el('div.peditor', top, el('div.pemain', stage, panel)));
  PE.ui = { canvas, overlay, stage, tabBody, drawTabs };
  drawTabs();
  peFillTab(tabBody);
  peStageEvents();
  new ResizeObserver(() => peRefresh()).observe(stage);
  peRefresh();
}

function peRefreshAll() { if (!PE.ui) return; PE.ui.drawTabs(); peFillTab(PE.ui.tabBody); peRefresh(); }

let peRaf = 0;
function peRefresh() {
  if (peRaf) return;
  peRaf = requestAnimationFrame(() => { peRaf = 0; peDraw(); });
}

function peDraw() {
  if (!PE || !PE.ui || !document.body.contains(PE.ui.canvas)) return;
  const { canvas, overlay, stage } = PE.ui;
  const sw = stage.clientWidth - 24, sh = stage.clientHeight - 24;
  const s = PE.s;
  overlay.innerHTML = '';
  const cropping = PE.tab === 'crop';
  let pic;
  if (PE.compare) pic = PE.small;
  else if (cropping) pic = peRender(PE.small, { ...s, crop: { x: 0, y: 0, w: 1, h: 1 }, texts: [], strokes: [] });
  else pic = peRender(PE.small, s);
  const k = Math.min(sw / pic.width, sh / pic.height);
  const W = Math.max(1, Math.round(pic.width * k)), H = Math.max(1, Math.round(pic.height * k));
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
  canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
  canvas.getContext('2d').drawImage(pic, 0, 0, canvas.width, canvas.height);
  const left = (stage.clientWidth - W) / 2, topY = (stage.clientHeight - H) / 2;
  canvas.style.left = left + 'px'; canvas.style.top = topY + 'px';
  PE.view = { left, top: topY, W, H };
  if (cropping && !PE.compare) peCropOverlay(overlay, left, topY, W, H);
  if (PE.tab === 'text' && PE.selText >= 0 && s.texts[PE.selText]) {
    const b = peTextBox(s.texts[PE.selText], W, H);
    overlay.appendChild(el('div.pesel', { style: { left: left + b.x * W + 'px', top: topY + b.y * H + 'px', width: b.w * W + 'px', height: b.h * H + 'px' } }));
  }
}

// ---------- crop box ----------
function peAspect() {
  const a = PE.s.aspect;
  if (a === 'free') return null;
  const o = peOriented(PE.small, { ...PE.s, straight: 0 });
  if (a === 'orig') return null;
  const [w, h] = a.split(':').map(Number);
  return (w / h) / (o.width / o.height); // in 0–1 units of the turned photo
}
function peFitAspect() {
  const r = peAspect();
  const c = PE.s.crop;
  if (PE.s.aspect === 'orig') { PE.s.crop = { x: 0, y: 0, w: 1, h: 1 }; return; }
  if (!r) return;
  let w = c.w, h = w / r;
  if (h > 1) { h = 1; w = h * r; }
  if (w > 1) { w = 1; h = w / r; }
  const cx = c.x + c.w / 2, cy = c.y + c.h / 2;
  PE.s.crop = { x: Math.max(0, Math.min(1 - w, cx - w / 2)), y: Math.max(0, Math.min(1 - h, cy - h / 2)), w, h };
}
function peCropOverlay(ov, left, top, W, H) {
  const c = PE.s.crop;
  const box = el('div.pecrop', { style: { left: left + c.x * W + 'px', top: top + c.y * H + 'px', width: c.w * W + 'px', height: c.h * H + 'px' } },
    el('div.pegrid'), ...['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'].map((h) => el('div.peh.h-' + h, { 'data-h': h })));
  ov.appendChild(box);
  let drag = null;
  box.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    box.setPointerCapture(e.pointerId);
    drag = { h: e.target.dataset.h || 'move', x: e.clientX, y: e.clientY, c: { ...PE.s.crop } };
  });
  box.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dx = (e.clientX - drag.x) / W, dy = (e.clientY - drag.y) / H;
    let { x, y, w, h } = drag.c;
    const r = peAspect();
    const min = 0.05;
    if (drag.h === 'move') { x = Math.max(0, Math.min(1 - w, x + dx)); y = Math.max(0, Math.min(1 - h, y + dy)); }
    else {
      if (drag.h.includes('e')) w = Math.max(min, Math.min(1 - x, w + dx));
      if (drag.h.includes('s')) h = Math.max(min, Math.min(1 - y, h + dy));
      if (drag.h.includes('w')) { const nx = Math.max(0, Math.min(x + w - min, x + dx)); w += x - nx; x = nx; }
      if (drag.h.includes('n')) { const ny = Math.max(0, Math.min(y + h - min, y + dy)); h += y - ny; y = ny; }
      if (r) { // keep the shape
        if (drag.h === 'n' || drag.h === 's') w = h * r; else h = w / r;
        if (x + w > 1) { w = 1 - x; h = w / r; }
        if (y + h > 1) { h = 1 - y; w = h * r; }
        if (drag.h.includes('w')) x = drag.c.x + drag.c.w - w;
        if (drag.h.includes('n')) y = drag.c.y + drag.c.h - h;
      }
    }
    PE.s.crop = { x, y, w, h };
    box.style.left = left + x * W + 'px'; box.style.top = top + y * H + 'px'; box.style.width = w * W + 'px'; box.style.height = h * H + 'px';
  });
  box.addEventListener('pointerup', () => { if (drag) { drag = null; pePush(); } });
}

// ---------- drawing and moving text on the picture ----------
function peStageEvents() {
  const { stage } = PE.ui;
  let cur = null, moveT = null;
  const pos = (e) => [(e.clientX - stage.getBoundingClientRect().left - PE.view.left) / PE.view.W, (e.clientY - stage.getBoundingClientRect().top - PE.view.top) / PE.view.H];
  stage.addEventListener('pointerdown', (e) => {
    if (PE.tab === 'draw') {
      const [x, y] = pos(e);
      cur = { color: PE.pen.color, size: PE.pen.size / 1000, alpha: PE.pen.alpha, pts: [[x, y]] };
      PE.s.strokes.push(cur);
      stage.setPointerCapture(e.pointerId);
      peRefresh();
    } else if (PE.tab === 'text') {
      const [x, y] = pos(e);
      const i = PE.s.texts.map((t, j) => [t, j]).reverse().find(([t]) => { const b = peTextBox(t, PE.view.W, PE.view.H); return x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h; });
      PE.selText = i ? i[1] : -1;
      if (i) { moveT = { j: i[1], dx: x - i[0].x, dy: y - i[0].y }; stage.setPointerCapture(e.pointerId); }
      peFillTab(PE.ui.tabBody);
      peRefresh();
    }
  });
  stage.addEventListener('pointermove', (e) => {
    if (cur) { cur.pts.push(pos(e)); peRefresh(); }
    else if (moveT) { const [x, y] = pos(e); const t = PE.s.texts[moveT.j]; t.x = Math.max(0, Math.min(1, x - moveT.dx)); t.y = Math.max(0, Math.min(1, y - moveT.dy)); peRefresh(); }
  });
  stage.addEventListener('pointerup', () => { if (cur || moveT) { cur = null; moveT = null; pePush(); } });
}

// ---------- side panel ----------
function peSlider(label, min, max, value, onInput, onChange, fmt) {
  const val = el('span.dim', fmt ? fmt(value) : String(value));
  const r = el('input.fill', { type: 'range', min, max, step: 1, value });
  r.addEventListener('input', () => { val.textContent = fmt ? fmt(+r.value) : r.value; paintRange(r); onInput(+r.value); });
  r.addEventListener('change', () => onChange && onChange(+r.value));
  r.addEventListener('dblclick', () => { r.value = min < 0 ? 0 : min; r.dispatchEvent(new Event('input')); r.dispatchEvent(new Event('change')); });
  setTimeout(() => paintRange(r), 0);
  return el('div.peslider', el('div.row', el('span', label), val), r);
}

function peFillTab(body) {
  body.innerHTML = '';
  const s = PE.s;
  const commit = () => { pePush(); peRefresh(); };
  if (PE.tab === 'crop') {
    body.appendChild(el('div.label', 'Shape'));
    const chips = el('div.row.wrap');
    for (const [k, t] of PE_ASPECTS) chips.appendChild(el('div.chip' + (s.aspect === k ? '.on' : ''), { onclick: () => { s.aspect = k; peFitAspect(); commit(); peFillTab(body); } }, t));
    body.appendChild(chips);
    body.appendChild(el('div.label', 'Turn and flip'));
    const swap = () => { s.crop = { x: s.crop.y, y: s.crop.x, w: s.crop.h, h: s.crop.w }; };
    body.appendChild(el('div.row.wrap',
      el('button.btn', { onclick: () => { s.rot = (s.rot + 270) % 360; swap(); peFitAspect(); commit(); } }, '⟲ Left'),
      el('button.btn', { onclick: () => { s.rot = (s.rot + 90) % 360; swap(); peFitAspect(); commit(); } }, '⟳ Right'),
      el('button.btn', { onclick: () => { s.flipH = !s.flipH; commit(); } }, '⇋ Flip'),
      el('button.btn', { onclick: () => { s.flipV = !s.flipV; commit(); } }, '⇵ Flip up-down')));
    body.appendChild(peSlider('Straighten', -45, 45, s.straight, (v) => { s.straight = v; peRefresh(); }, commit, (v) => v + '°'));
    body.appendChild(el('button.btn', { style: { marginTop: '12px' }, onclick: () => { s.crop = { x: 0, y: 0, w: 1, h: 1 }; s.aspect = 'free'; commit(); peFillTab(body); } }, 'Undo the crop'));
    body.appendChild(el('p.dim', { style: { fontSize: '12px', marginTop: '14px' } }, 'Drag the corners or edges of the box to crop; drag inside it to move it.'));
  } else if (PE.tab === 'adjust') {
    for (const [k, t, mn, mx] of PE_ADJ) body.appendChild(peSlider(t, mn, mx, s.adj[k], (v) => { s.adj[k] = v; peRefresh(); }, commit));
    body.appendChild(el('p.dim', { style: { fontSize: '12px' } }, 'Double-click a slider to put it back.'));
  } else if (PE.tab === 'filters') {
    const grid = el('div.pefilters');
    const thumbSrc = (() => { const k = 140 / Math.max(PE.small.width, PE.small.height); const c = document.createElement('canvas'); c.width = Math.round(PE.small.width * k); c.height = Math.round(PE.small.height * k); c.getContext('2d').drawImage(PE.small, 0, 0, c.width, c.height); return c; })();
    for (const [k, t] of PE_FILTERS) {
      const prev = peRender(thumbSrc, { ...s, filter: k, texts: [], strokes: [], adj: { ...s.adj, sharpen: 0 } }, 120);
      prev.className = 'pefprev';
      grid.appendChild(el('div.pefilter' + (s.filter === k ? '.on' : ''), { onclick: () => { s.filter = k; commit(); peFillTab(body); } }, prev, el('div', t)));
    }
    body.appendChild(grid);
  } else if (PE.tab === 'text') {
    body.appendChild(el('button.btn.primary', { onclick: async () => {
      const t = await prompt('Add text', '', 'Your text');
      if (!t) return;
      s.texts.push({ text: t, x: 0.5, y: 0.5, size: 0.08, color: '#ffffff', bold: true, shadow: true, font: 'Segoe UI', outline: '', bg: '' });
      PE.selText = s.texts.length - 1;
      commit(); peFillTab(body);
    } }, '+ Add text'));
    const t = s.texts[PE.selText];
    if (!t) { body.appendChild(el('p.dim', { style: { fontSize: '12px', marginTop: '12px' } }, s.texts.length ? 'Click a text on the photo to change it or drag it to move it.' : 'Add text, then drag it where you want it.')); return; }
    const ta = el('textarea', { rows: 2, value: t.text, style: { width: '100%', marginTop: '12px' } });
    ta.value = t.text;
    ta.addEventListener('input', () => { t.text = ta.value || ' '; peRefresh(); });
    ta.addEventListener('change', commit);
    body.appendChild(ta);
    body.appendChild(peSlider('Size', 2, 40, Math.round(t.size * 100), (v) => { t.size = v / 100; peRefresh(); }, commit));
    const colors = ['#ffffff', '#000000', '#ffd400', '#ff3b30', '#34c759', '#0a84ff', '#bf5af2', '#ff9f0a', '#00e5ff'];
    const sw = (key, list, allowNone) => { const row = el('div.row.wrap'); if (allowNone) row.appendChild(el('div.swatch.none' + (!t[key] ? '.on' : ''), { title: 'None', onclick: () => { t[key] = ''; commit(); peFillTab(body); } }, '∅')); for (const c of list) row.appendChild(el('div.swatch' + (t[key] === c ? '.on' : ''), { style: { background: c }, onclick: () => { t[key] = c; commit(); peFillTab(body); } })); return row; };
    body.append(el('div.label', 'Colour'), sw('color', colors), el('div.label', 'Outline'), sw('outline', colors, true), el('div.label', 'Background'), sw('bg', colors, true));
    const fonts = ['Segoe UI', 'Arial', 'Georgia', 'Times New Roman', 'Courier New', 'Impact', 'Comic Sans MS', 'Trebuchet MS'];
    const fsel = el('select', ...fonts.map((f) => el('option', { value: f, selected: t.font === f }, f)));
    fsel.addEventListener('change', () => { t.font = fsel.value; commit(); });
    body.append(el('div.label', 'Font'), fsel);
    body.append(el('div.row.wrap', { style: { marginTop: '10px' } },
      el('button.btn' + (t.bold ? '.on' : ''), { onclick: () => { t.bold = !t.bold; commit(); peFillTab(body); } }, 'Bold'),
      el('button.btn' + (t.shadow ? '.on' : ''), { onclick: () => { t.shadow = !t.shadow; commit(); peFillTab(body); } }, 'Shadow'),
      el('button.btn', { onclick: () => { s.texts.splice(PE.selText, 1); PE.selText = -1; commit(); peFillTab(body); } }, 'Delete text')));
  } else if (PE.tab === 'draw') {
    PE.pen = PE.pen || { color: '#ff3b30', size: 8, alpha: 1 };
    const colors = ['#ffffff', '#000000', '#ffd400', '#ff3b30', '#34c759', '#0a84ff', '#bf5af2', '#ff9f0a', '#00e5ff'];
    const row = el('div.row.wrap');
    for (const c of colors) row.appendChild(el('div.swatch' + (PE.pen.color === c ? '.on' : ''), { style: { background: c }, onclick: () => { PE.pen.color = c; peFillTab(body); } }));
    body.append(el('div.label', 'Pen colour'), row,
      peSlider('Pen size', 1, 60, PE.pen.size, (v) => { PE.pen.size = v; }),
      peSlider('See-through', 0, 90, Math.round((1 - PE.pen.alpha) * 100), (v) => { PE.pen.alpha = 1 - v / 100; }, null, (v) => v + '%'),
      el('div.row.wrap', { style: { marginTop: '10px' } },
        el('button.btn', { onclick: () => { if (s.strokes.length) { s.strokes.pop(); commit(); } } }, 'Remove last line'),
        el('button.btn', { onclick: () => { if (s.strokes.length) { s.strokes = []; commit(); } } }, 'Clear drawing')),
      el('p.dim', { style: { fontSize: '12px', marginTop: '12px' } }, 'Draw on the photo with the mouse.'));
  }
}

// ---------- saving ----------
async function peSave(replace) {
  const body = el('div');
  const fmtSel = el('select', el('option', { value: 'jpg' }, 'JPG (smaller, for photos)'), el('option', { value: 'png' }, 'PNG (best quality, bigger)'), el('option', { value: 'webp' }, 'WebP (small, modern)'));
  const ext = PE.path.split('.').pop().toLowerCase();
  fmtSel.value = ext === 'png' ? 'png' : ext === 'webp' ? 'webp' : 'jpg';
  const sizeSel = el('select', el('option', { value: '0' }, 'Full size'), el('option', { value: '3840' }, 'Large (3840 px)'), el('option', { value: '2048' }, 'Medium (2048 px)'), el('option', { value: '1080' }, 'Small (1080 px, for sharing)'));
  let q = 92;
  body.append(el('div.label', 'Format'), fmtSel, el('div.label', 'Size'), sizeSel, peSlider('Quality', 50, 100, q, (v) => { q = v; }, null, (v) => v + '%'));
  if (replace) body.appendChild(el('p.dim', { style: { marginTop: '12px' } }, 'This replaces the original photo. Use "Save a copy" to keep the original.'));
  const ok = await dialog({ title: replace ? 'Save over the original' : 'Save a copy', body, buttons: [{ text: 'Cancel', value: false }, { text: replace ? 'Replace' : 'Choose where…', primary: true, value: true }] });
  if (!ok) return;
  const fmt = fmtSel.value;
  let dest = PE.path;
  if (!replace || fmt !== (ext === 'jpeg' || ext === 'jfif' ? 'jpg' : ext)) {
    dest = await api.saveDialog({ title: 'Save the photo', defaultPath: PE.path.replace(/\.[^.\\/]+$/, '') + (replace ? '' : ' (edited)') + '.' + fmt,
      filters: [{ name: fmt.toUpperCase(), extensions: [fmt] }] });
    if (!dest) return;
  }
  const wait = progressBox('Saving the photo…');
  wait.set(40, 'Working on the full-size photo');
  await new Promise((r) => setTimeout(r, 30));
  try {
    const full = peRender(PE.img, PE.s);
    const max = +sizeSel.value;
    let out = full;
    if (max && Math.max(full.width, full.height) > max) {
      const k = max / Math.max(full.width, full.height);
      out = document.createElement('canvas');
      out.width = Math.round(full.width * k); out.height = Math.round(full.height * k);
      const x = out.getContext('2d'); x.imageSmoothingQuality = 'high'; x.drawImage(full, 0, 0, out.width, out.height);
    }
    if (fmt === 'jpg') { const f = document.createElement('canvas'); f.width = out.width; f.height = out.height; const x = f.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, f.width, f.height); x.drawImage(out, 0, 0); out = f; }
    const mime = { jpg: 'image/jpeg', png: 'image/png', webp: 'image/webp' }[fmt];
    await api.writeData(dest, out.toDataURL(mime, q / 100));
    wait.close();
    PE.dirty = false;
    PICS = null;
    toast('Saved: ' + dest, 4000);
  } catch (e) {
    wait.close();
    alertBox('Couldn\'t save', String(e.message || e));
  }
}

function peKeys(e) {
  if (!PE) return false;
  const k = e.key.toLowerCase();
  if ((e.ctrlKey || e.metaKey) && k === 'z') { e.preventDefault(); if (e.shiftKey) peRedo(); else peUndo(); return true; }
  if ((e.ctrlKey || e.metaKey) && k === 'y') { e.preventDefault(); peRedo(); return true; }
  if ((e.ctrlKey || e.metaKey) && k === 's') { e.preventDefault(); peSave(false); return true; }
  if (k === 'delete' && PE.tab === 'text' && PE.selText >= 0) { PE.s.texts.splice(PE.selText, 1); PE.selText = -1; pePush(); peFillTab(PE.ui.tabBody); peRefresh(); return true; }
  if (k === 'escape') { goBack(); return true; }
  return true; // keep letters from jumping around the page while editing
}
