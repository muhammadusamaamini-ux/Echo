// Collage: 2 to 9 photos, many arrangements for each number, any shape (square, story, wide…), spacing,
// rounded corners and background colour. Drag a photo to move it in its box, scroll to zoom it, and swap boxes.
'use strict';

// ---------- arrangements ----------
// A layout is a list of boxes [x, y, w, h] in 0–1 of the collage.
function rowsLayout(counts, sizes) {
  const total = sizes ? sizes.reduce((a, b) => a + b, 0) : counts.length;
  const out = [];
  let y = 0;
  counts.forEach((n, r) => {
    const h = (sizes ? sizes[r] : 1) / total;
    for (let i = 0; i < n; i++) out.push([i / n, y, 1 / n, h]);
    y += h;
  });
  return out;
}
const flip = (lay) => lay.map(([x, y, w, h]) => [y, x, h, w]); // rows ↔ columns

function compositions(n, maxParts, maxPart) {
  const out = [];
  const rec = (left, acc) => {
    if (!left) { out.push(acc); return; }
    if (acc.length >= maxParts) return;
    for (let k = 1; k <= Math.min(maxPart, left); k++) rec(left - k, acc.concat(k));
  };
  rec(n, []);
  return out;
}

function layoutsFor(n) {
  const L = [];
  const add = (lay) => { if (lay.length === n) L.push(lay.map((r) => r.map((v) => Math.round(v * 10000) / 10000))); };
  if (n === 1) { add([[0, 0, 1, 1]]); return L; }
  // a big photo with the others beside it / under it
  const rest = n - 1;
  const stack = (k, x0, y0, w, h, vertical) => Array.from({ length: k }, (_, i) => vertical ? [x0, y0 + i * h / k, w, h / k] : [x0 + i * w / k, y0, w / k, h]);
  if (rest <= 4) {
    add([[0, 0, 0.62, 1], ...stack(rest, 0.62, 0, 0.38, 1, true)]);
    add([[0, 0, 1, 0.62], ...stack(rest, 0, 0.62, 1, 0.38, false)]);
    add([...stack(rest, 0, 0, 0.38, 1, true), [0.38, 0, 0.62, 1]]);
    add([...stack(rest, 0, 0, 1, 0.38, false), [0, 0.38, 1, 0.62]]);
  } else {
    const a = Math.ceil(rest / 2), b = rest - a;
    add([[0, 0, 0.55, 1], ...stack(a, 0.55, 0, 0.225, 1, true), ...stack(b, 0.775, 0, 0.225, 1, true)]);
    add([[0, 0, 1, 0.55], ...stack(a, 0, 0.55, 1, 0.225, false), ...stack(b, 0, 0.775, 1, 0.225, false)]);
  }
  // big photo in the middle with the others around it
  if (n === 5) add([[0, 0, 0.5, 0.3], [0.5, 0, 0.5, 0.3], [0, 0.3, 1, 0.4], [0, 0.7, 0.5, 0.3], [0.5, 0.7, 0.5, 0.3]]);
  if (n === 5) add([[0, 0, 0.3, 0.5], [0, 0.5, 0.3, 0.5], [0.3, 0, 0.4, 1], [0.7, 0, 0.3, 0.5], [0.7, 0.5, 0.3, 0.5]]);
  if (n === 9 || n === 7) {
    const c = [0.25, 0.25, 0.5, 0.5];
    const ring = n === 9
      ? [[0, 0, 0.25, 0.25], [0.25, 0, 0.5, 0.25], [0.75, 0, 0.25, 0.25], [0, 0.25, 0.25, 0.5], [0.75, 0.25, 0.25, 0.5], [0, 0.75, 0.25, 0.25], [0.25, 0.75, 0.5, 0.25], [0.75, 0.75, 0.25, 0.25]]
      : [[0, 0, 0.5, 0.25], [0.5, 0, 0.5, 0.25], [0, 0.25, 0.25, 0.5], [0.75, 0.25, 0.25, 0.5], [0, 0.75, 0.5, 0.25], [0.5, 0.75, 0.5, 0.25]];
    add([c, ...ring]);
  }
  if (n === 3) { add([[0, 0, 0.5, 0.5], [0, 0.5, 0.5, 0.5], [0.5, 0, 0.5, 1]].reverse()); }
  // rows (and the same turned into columns), equal and with a taller first row
  for (const comp of compositions(n, 4, 4)) {
    if (comp.length === 1 && n > 4) continue;
    add(rowsLayout(comp));
    add(flip(rowsLayout(comp)));
    if (comp.length === 2 && comp[0] < comp[1]) add(rowsLayout(comp, [3, 2]));
    if (comp.length === 2 && comp[0] > comp[1]) add(rowsLayout(comp, [2, 3]));
  }
  // remove duplicates, keep the first 24
  const seen = new Set();
  return L.filter((lay) => { const k = JSON.stringify(lay.slice().sort()); if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 24);
}

const CL_ASPECTS = [['1:1', 'Square'], ['4:5', '4:5 (post)'], ['9:16', '9:16 (story)'], ['3:4', '3:4'], ['4:3', '4:3'], ['16:9', '16:9 (wide)'], ['2:3', '2:3'], ['3:2', '3:2']];
const CL_BG = ['#ffffff', '#000000', '#1c1c28', '#f4efe6', '#ffd6e0', '#d6ecff', '#e3f7d4', '#ffe9b3', '#7b5cff', '#ff5c8a'];
let CL = null;

function collageView(arg) {
  const add = (arg && arg.photos) || [];
  if (!CL || (add.length && !add.every((p) => CL.photos.some((x) => x.path === p)))) {
    if (add.length || !CL) CL = { photos: [], layout: 0, aspect: '1:1', gap: 12, radius: 0, bg: '#ffffff', cells: [], sel: -1, swap: false };
    if (arg) arg.photos = null; // only once
    clAddPaths(add);
  }
  clBuild();
}

async function clAddPaths(paths) {
  for (const p of paths) {
    if (CL.photos.length >= 9) { toast('A collage can have up to 9 photos'); break; }
    if (CL.photos.some((x) => x.path === p)) continue;
    const ph = { path: p, img: new Image() };
    ph.img.onload = () => clRefresh();
    ph.img.src = await api.fileUrl(p);
    CL.photos.push(ph);
  }
  clReset();
}
/** Every box gets its own photo again (after adding or removing photos). */
function clReset() {
  CL.cells = CL.photos.map((_, i) => ({ p: i, zoom: 1, ox: 0, oy: 0 }));
  CL.layout = Math.min(CL.layout, Math.max(0, clLayouts().length - 1));
  CL.sel = -1;
  if (NAV.view === 'collage') { main.innerHTML = ''; clBuild(); }
}

async function clPickPhotos() {
  const r = await api.openDialog({ title: 'Choose photos for the collage', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Photos', extensions: ['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'avif', 'jfif'] }] });
  if (r && r.length) clAddPaths(r);
}

function clBuild() {
  if (!CL) return;
  const n = CL.photos.length;
  const top = el('div.pebar', iconBtn('back', 'Back', () => goBack()), el('div.pvtt', el('div.pvtitle', 'Collage'), el('div.dim', n ? plural(n, 'photo') : 'Add 2 to 9 photos')),
    el('button.btn', { onclick: clPickPhotos }, '+ Add photos'),
    el('button.btn', { onclick: () => go('photos') }, 'Choose from Photos'),
    n ? el('button.btn', { onclick: async () => { if (await confirmBox('Start a new collage?', 'The photos are removed from this collage (not from your PC).', 'Start new')) { CL = null; refreshView(); } } }, 'New') : null,
    el('button.btn.primary', { onclick: clSave, disabled: n < 2 }, 'Save collage'));
  if (n < 2) {
    main.appendChild(el('div.peditor', top, el('div.empty', el('p', n ? 'Add at least one more photo.' : 'Add 2 to 9 photos to make a collage.'),
      el('p.dim', 'Tip: in Photos, open a folder, press Select, tick photos and press "Make a collage".'),
      el('button.btn.primary', { onclick: clPickPhotos }, '+ Add photos'))));
    return;
  }
  const stage = el('div.pestage');
  const canvas = el('canvas.pecanvas');
  stage.appendChild(canvas);
  const panel = el('div.pepanel');
  main.appendChild(el('div.peditor', top, el('div.pemain', stage, panel)));
  CL.ui = { stage, canvas, panel };
  clPanel();
  clStageEvents();
  new ResizeObserver(() => clRefresh()).observe(stage);
  clRefresh();
}

function clPanel() {
  const panel = CL.ui.panel;
  panel.innerHTML = '';
  const lays = clLayouts();
  const nBox = layoutsFor(CL.photos.length).length;
  const [ar, br] = clSize(100);
  const mini = (lay, i) => {
    const box = el('div.cllay' + (CL.layout === i ? '.on' : ''), { title: lay.name || 'Boxes', onclick: () => { CL.layout = i; clPanel(); clRefresh(); } });
    const NS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${ar} ${br}`);
    for (const c of clCells(ar, br, lay, 1.2)) {
      const e = document.createElementNS(NS, c.kind === 'rect' ? 'rect' : 'polygon');
      if (c.kind === 'rect') { e.setAttribute('x', c.l); e.setAttribute('y', c.t); e.setAttribute('width', Math.max(0, c.w)); e.setAttribute('height', Math.max(0, c.h)); e.setAttribute('rx', 1.5); }
      else e.setAttribute('points', c.pts.map((p) => p.join(',')).join(' '));
      if (c.card) e.setAttribute('class', 'card');
      svg.appendChild(e);
    }
    box.appendChild(svg);
    return box;
  };
  panel.appendChild(el('div.label', `Boxes (${nBox})`));
  const grid = el('div.cllays');
  lays.slice(0, nBox).forEach((lay, i) => grid.appendChild(mini(lay, i)));
  panel.appendChild(grid);
  panel.appendChild(el('div.label', `Shapes (${lays.length - nBox}) — circles, flower, heart, star, waves…`));
  const grid2 = el('div.cllays');
  lays.slice(nBox).forEach((lay, j) => grid2.appendChild(mini(lay, nBox + j)));
  panel.appendChild(grid2);
  panel.appendChild(el('div.label', 'Shape'));
  const asp = el('div.row.wrap');
  for (const [k, t] of CL_ASPECTS) asp.appendChild(el('div.chip' + (CL.aspect === k ? '.on' : ''), { onclick: () => { CL.aspect = k; clPanel(); clRefresh(); } }, t));
  panel.appendChild(asp);
  panel.appendChild(peSlider('Spacing', 0, 60, CL.gap, (v) => { CL.gap = v; clRefresh(); }));
  panel.appendChild(peSlider('Rounded corners', 0, 60, CL.radius, (v) => { CL.radius = v; clRefresh(); }));
  panel.appendChild(el('div.label', 'Background'));
  const bg = el('div.row.wrap');
  for (const c of CL_BG) bg.appendChild(el('div.swatch' + (CL.bg === c ? '.on' : ''), { style: { background: c }, onclick: () => { CL.bg = c; clPanel(); clRefresh(); } }));
  const pick = el('input', { type: 'color', value: CL.bg, title: 'Any colour' });
  pick.addEventListener('input', () => { CL.bg = pick.value; clRefresh(); });
  bg.appendChild(pick);
  panel.appendChild(bg);
  // the chosen box
  const c = CL.cells[CL.sel];
  panel.appendChild(el('div.label', 'Photo in a box'));
  if (!c) { panel.appendChild(el('p.dim', { style: { fontSize: '12px' } }, 'Click a box to change its photo. Drag a photo to move it inside its box; scroll to zoom it.')); return; }
  panel.appendChild(peSlider('Zoom', 100, 400, Math.round(c.zoom * 100), (v) => { c.zoom = v / 100; clRefresh(); }, null, (v) => v + '%'));
  panel.appendChild(el('div.row.wrap',
    el('button.btn' + (CL.swap ? '.on' : ''), { onclick: () => { CL.swap = !CL.swap; clPanel(); } }, CL.swap ? 'Now click the other box…' : '⇄ Swap with another box'),
    el('button.btn', { onclick: async () => {
      const r = await api.openDialog({ title: 'Choose a photo', properties: ['openFile'], filters: [{ name: 'Photos', extensions: ['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'avif', 'jfif'] }] });
      if (!r || !r[0]) return;
      const ph = CL.photos[c.p];
      ph.path = r[0]; ph.img = new Image(); ph.img.onload = () => clRefresh(); ph.img.src = await api.fileUrl(r[0]);
      c.zoom = 1; c.ox = 0; c.oy = 0;
    } }, 'Replace photo'),
    el('button.btn', { onclick: () => { c.zoom = 1; c.ox = 0; c.oy = 0; clPanel(); clRefresh(); } }, 'Fit again'),
    el('button.btn', { onclick: () => { CL.photos.splice(c.p, 1); clReset(); } }, 'Remove photo')));
}

function clSize(longSide) {
  const [a, b] = CL.aspect.split(':').map(Number);
  return a >= b ? [longSide, Math.round(longSide * b / a)] : [Math.round(longSide * a / b), longSide];
}

/** All arrangements for the current photos: boxes first, then shapes (circles, flower, heart…). */
let clLayCache = { key: '', list: [] };
function clLayouts() {
  const key = CL.photos.length + '|' + CL.aspect;
  if (clLayCache.key === key) return clLayCache.list; // worked out once per number of photos and shape
  const [a, b] = clSize(1000);
  clLayCache = { key, list: [...layoutsFor(CL.photos.length).map((rects) => ({ rects })), ...shapeLayouts(CL.photos.length, a / b)] };
  return clLayCache.list;
}

/** Where each photo goes, in pixels, for a collage W×H: boxes {kind:'rect', l, t, w, h} or shapes {kind:'poly', pts, card}. */
function clCells(W, H, lay, gapPx) {
  lay = lay || clLayouts()[CL.layout] || clLayouts()[0];
  if (!lay) return [];
  const g = gapPx != null ? gapPx : CL.gap / 1000 * Math.min(W, H);
  if (lay.rects) return lay.rects.map(([x, y, w, h]) => ({ kind: 'rect', l: x * (W - g) + g, t: y * (H - g) + g, w: w * (W - g) - g, h: h * (H - g) - g }));
  return lay.cells.map((c) => {
    const pts = c.pts.map(([x, y]) => [x * W, y * H]);
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const l = Math.min(...xs), t = Math.min(...ys);
    return { kind: 'poly', pts, card: !!c.card, l, t, w: Math.max(...xs) - l, h: Math.max(...ys) - t };
  });
}
const clPath = (x, pts) => { x.beginPath(); pts.forEach(([a, b], i) => (i ? x.lineTo(a, b) : x.moveTo(a, b))); x.closePath(); };
function clInside(c, px, py) {
  if (c.kind === 'rect') return px >= c.l && px <= c.l + c.w && py >= c.t && py <= c.t + c.h;
  let inside = false;
  for (let i = 0, j = c.pts.length - 1; i < c.pts.length; j = i++) {
    const [xi, yi] = c.pts[i], [xj, yj] = c.pts[j];
    if ((yi > py) !== (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

function clDraw(x, W, H, forSave) {
  x.fillStyle = CL.bg;
  x.fillRect(0, 0, W, H);
  const r = CL.radius / 1000 * Math.min(W, H);
  const g = CL.gap / 1000 * Math.min(W, H);
  clCells(W, H).forEach((c, i) => {
    const cell = CL.cells[i];
    const ph = cell && CL.photos[cell.p];
    let { l, t, w, h } = c;
    x.save();
    if (c.kind === 'rect') { x.beginPath(); if (x.roundRect) x.roundRect(l, t, w, h, r); else x.rect(l, t, w, h); }
    else if (c.card) { // a photo print: white frame and a soft shadow
      x.save(); x.shadowColor = 'rgba(0,0,0,.35)'; x.shadowBlur = Math.min(W, H) * 0.02; x.shadowOffsetY = Math.min(W, H) * 0.006;
      clPath(x, c.pts); x.fillStyle = '#fff'; x.fill(); x.restore();
      const cx = c.pts.reduce((a, p) => a + p[0], 0) / 4, cy = c.pts.reduce((a, p) => a + p[1], 0) / 4;
      const inner = c.pts.map(([a, b]) => [cx + (a - cx) * 0.9, cy + (b - cy) * 0.9]);
      clPath(x, inner);
      const xs = inner.map((p) => p[0]), ys = inner.map((p) => p[1]);
      l = Math.min(...xs); t = Math.min(...ys); w = Math.max(...xs) - l; h = Math.max(...ys) - t;
    } else clPath(x, c.pts);
    x.clip();
    if (ph && ph.img.complete && ph.img.naturalWidth) {
      const iw = ph.img.naturalWidth, ih = ph.img.naturalHeight;
      const k = Math.max(w / iw, h / ih) * cell.zoom;
      const dw = iw * k, dh = ih * k;
      const ex = (dw - w) / 2, ey = (dh - h) / 2;
      x.imageSmoothingQuality = 'high';
      x.drawImage(ph.img, l - ex + cell.ox * ex, t - ey + cell.oy * ey, dw, dh);
    } else { x.fillStyle = 'rgba(128,128,128,.25)'; x.fillRect(l, t, w, h); }
    x.restore();
    if (c.kind === 'poly' && !c.card && g > 0) { // spacing between shapes
      x.save(); clPath(x, c.pts); x.strokeStyle = CL.bg; x.lineWidth = g; x.lineJoin = 'round'; x.stroke(); x.restore();
    }
    if (!forSave && i === CL.sel) {
      x.save(); x.strokeStyle = '#00e5ff'; x.lineWidth = 3; x.setLineDash([8, 5]);
      if (c.kind === 'rect') x.strokeRect(l + 1.5, t + 1.5, w - 3, h - 3); else { clPath(x, c.pts); x.stroke(); }
      x.restore();
    }
  });
}

let clRaf = 0;
function clRefresh() { if (clRaf) return; clRaf = requestAnimationFrame(() => { clRaf = 0; clPaint(); }); }
function clPaint() {
  if (!CL || !CL.ui || !document.body.contains(CL.ui.canvas)) return;
  const { stage, canvas } = CL.ui;
  const sw = stage.clientWidth - 32, sh = stage.clientHeight - 32;
  const [a, b] = clSize(1000);
  const k = Math.min(sw / a, sh / b);
  const W = Math.round(a * k), H = Math.round(b * k);
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
  canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
  canvas.style.left = (stage.clientWidth - W) / 2 + 'px'; canvas.style.top = (stage.clientHeight - H) / 2 + 'px';
  const x = canvas.getContext('2d');
  x.setTransform(dpr, 0, 0, dpr, 0, 0);
  clDraw(x, W, H, false);
  CL.view = { W, H };
}

function clStageEvents() {
  const { canvas } = CL.ui;
  const hit = (e) => {
    const r = canvas.getBoundingClientRect();
    const px = e.clientX - r.left, py = e.clientY - r.top;
    const cells = clCells(CL.view.W, CL.view.H);
    for (let i = cells.length - 1; i >= 0; i--) if (clInside(cells[i], px, py)) return i; // the top one first (e.g. the circle in the middle)
    return -1;
  };
  let drag = null;
  canvas.addEventListener('pointerdown', (e) => {
    const i = hit(e);
    if (i < 0) { CL.sel = -1; CL.swap = false; clPanel(); clRefresh(); return; }
    if (CL.swap && CL.sel >= 0 && i !== CL.sel) { // swap the two boxes' photos
      const a = CL.cells[CL.sel], b = CL.cells[i];
      [CL.cells[CL.sel], CL.cells[i]] = [b, a];
      CL.swap = false; CL.sel = i; clPanel(); clRefresh(); return;
    }
    if (CL.sel !== i) { CL.sel = i; CL.swap = false; clPanel(); }
    const { w, h } = clCells(CL.view.W, CL.view.H)[i];
    drag = { i, x: e.clientX, y: e.clientY, ox: CL.cells[i].ox, oy: CL.cells[i].oy, w, h };
    canvas.setPointerCapture(e.pointerId);
    clRefresh();
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const c = CL.cells[drag.i];
    const ph = CL.photos[c.p];
    if (!ph || !ph.img.naturalWidth) return;
    const k = Math.max(drag.w / ph.img.naturalWidth, drag.h / ph.img.naturalHeight) * c.zoom;
    const ex = Math.max(1, (ph.img.naturalWidth * k - drag.w) / 2), ey = Math.max(1, (ph.img.naturalHeight * k - drag.h) / 2);
    c.ox = Math.max(-1, Math.min(1, drag.ox + (e.clientX - drag.x) / ex));
    c.oy = Math.max(-1, Math.min(1, drag.oy + (e.clientY - drag.y) / ey));
    clRefresh();
  });
  canvas.addEventListener('pointerup', () => { drag = null; });
  canvas.addEventListener('wheel', (e) => {
    const i = hit(e);
    if (i < 0) return;
    e.preventDefault();
    const c = CL.cells[i];
    c.zoom = Math.max(1, Math.min(4, c.zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
    if (CL.sel !== i) CL.sel = i;
    clPanel();
    clRefresh();
  }, { passive: false });
}

async function clSave() {
  const body = el('div');
  const sizeSel = el('select', el('option', { value: '2048' }, 'Large (2048 px)'), el('option', { value: '4096' }, 'Extra large (4096 px)'), el('option', { value: '1080' }, 'Small (1080 px, for sharing)'));
  const fmtSel = el('select', el('option', { value: 'jpg' }, 'JPG'), el('option', { value: 'png' }, 'PNG (best quality)'));
  body.append(el('div.label', 'Size'), sizeSel, el('div.label', 'Format'), fmtSel);
  if (!await dialog({ title: 'Save the collage', body, buttons: [{ text: 'Cancel', value: false }, { text: 'Choose where…', primary: true, value: true }] })) return;
  const fmt = fmtSel.value;
  const sys = await api.info();
  const dest = await api.saveDialog({ title: 'Save the collage', defaultPath: (L.prefs.collageDir || sys.pictures) + '\\Collage ' + new Date().toISOString().slice(0, 10) + '.' + fmt,
    filters: [{ name: fmt.toUpperCase(), extensions: [fmt] }] });
  if (!dest) return;
  L.prefs.collageDir = dest.replace(/[\\/][^\\/]*$/, '');
  save();
  const [W, H] = clSize(+sizeSel.value);
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  clDraw(c.getContext('2d'), W, H, true);
  await api.writeData(dest, c.toDataURL(fmt === 'png' ? 'image/png' : 'image/jpeg', 0.93));
  PICS = null;
  toast('Collage saved: ' + dest, 4000);
}

function clKeys(e) {
  if (!CL) return false;
  if (e.key === 'Escape') { if (CL.swap) { CL.swap = false; clPanel(); } else goBack(); return true; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); clSave(); return true; }
  return true;
}
