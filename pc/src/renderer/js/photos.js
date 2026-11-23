// Photos: your pictures by folder, a viewer (zoom, next/previous, slideshow), and the way into the
// photo editor (peditor.js) and the collage maker (collage.js).
'use strict';

let PICS = null; // what was found last time (scanned again with ↻ or when folders change)
const PSEL = { on: false, paths: new Set() }; // "Select" mode: tick photos for a collage or to delete

async function scanPhotos(force) {
  if (PICS && !force) return PICS;
  const sys = await api.info();
  const dirs = L.photoDirs.length ? L.photoDirs : [sys.pictures, sys.desktop, sys.downloads];
  PICS = await api.scanImages(dirs);
  return PICS;
}

const PHOTO_SORTS = [['newest', 'Newest first'], ['oldest', 'Oldest first'], ['name', 'Name (A–Z)'], ['nameDesc', 'Name (Z–A)'],
  ['biggest', 'Size (largest first)'], ['smallest', 'Size (smallest first)']];
const PFOLDER_SORTS = [['name', 'Name (A–Z)'], ['nameDesc', 'Name (Z–A)'], ['most', 'Most photos first'], ['newest', 'Newest photos first'], ['biggest', 'Size (largest first)']];

function sortPhotos(list, mode) {
  const m = { newest: 'modified', oldest: 'modifiedOld' }[mode] || mode;
  return sortVideos(list, m); // same fields as videos: name, mtime, size
}

// small pictures load as they scroll into view
const photoObserver = new IntersectionObserver((entries) => {
  for (const en of entries) {
    if (!en.isIntersecting) continue;
    const img = en.target;
    photoObserver.unobserve(img);
    api.imageThumb(img._p.path, img._p.mtime).then((u) => { if (u) img.src = u; });
  }
}, { root: null, rootMargin: '600px' });
function thumbImg(p) {
  const img = el('img.pthumb', { alt: '', draggable: false });
  img._p = p;
  photoObserver.observe(img);
  return img;
}

function photosView(arg) {
  const folderPath = arg && arg.folder;
  if (folderPath) main.appendChild(el('div.crumbs', el('a', { onclick: () => go('photos') }, 'Photos')));
  const selBtn = folderPath ? el('button.btn' + (PSEL.on ? '.on' : ''), { onclick: () => { PSEL.on = !PSEL.on; PSEL.paths.clear(); refreshView(); } }, PSEL.on ? 'Done' : 'Select') : null;
  main.appendChild(head(folderPath ? folderPath.split(/[\\/]/).pop() : 'Photos',
    folderPath ? sortButton('p:' + folderPath, PHOTO_SORTS, 'newest') : sortButton('pfolders', PFOLDER_SORTS, 'name'),
    selBtn,
    folderPath ? null : el('button.btn', { onclick: addPhotoFolder }, '+ Photo folder'),
    el('button.btn', { title: 'Look for new photos', onclick: () => { PICS = null; refreshView(); } }, '↻'),
    el('button.btn', { onclick: openPhotoFiles }, 'Open photo'),
    el('button.btn', { onclick: () => go('peditor') }, '✏ Edit a photo'),
    el('button.btn.primary', { onclick: () => go('collage') }, '▦ Make a collage')));
  const box = el('div', el('div.empty', 'Looking for photos…'));
  main.appendChild(box);
  scanPhotos(false).then((pics) => {
    if (NAV.view !== 'photos') return;
    box.innerHTML = '';
    if (!folderPath) {
      const groups = new Map();
      for (const p of pics) { if (!groups.has(p.folderPath)) groups.set(p.folderPath, []); groups.get(p.folderPath).push(p); }
      box.appendChild(el('div.sub', { style: { marginBottom: '14px' } }, `${plural(pics.length, 'photo')} in ${plural(groups.size, 'folder')} · looking in: `,
        ...(L.photoDirs.length ? L.photoDirs.map((d, i) => el('span', i ? ', ' : '', el('a', { style: { color: 'var(--glow)', cursor: 'pointer' }, title: 'Right-click to stop looking here',
          oncontextmenu: (e) => menu(e, [['Stop looking in this folder', () => { L.photoDirs.splice(i, 1); save(); PICS = null; refreshView(); }]]) }, d)))
          : ['your Pictures, Desktop and Downloads folders'])));
      if (!pics.length) { box.appendChild(el('div.empty', 'No photos found. Use "+ Photo folder" to choose where your photos are.')); return; }
      const wrap = el('div.cards.photo');
      for (const [fp, ps] of sortVideoFolders([...groups.entries()], getSort('pfolders', 'name'))) {
        const newest = ps.slice().sort((a, b) => b.mtime - a.mtime)[0];
        wrap.appendChild(el('div.card', { onclick: () => go('photos', { folder: fp }) },
          el('div.art-wrap', el('div.art', thumbImg(newest))), el('div.name', fp.split(/[\\/]/).pop()),
          el('div.meta', plural(ps.length, 'photo') + ' · ' + fp)));
      }
      box.appendChild(wrap);
      return;
    }
    const ps = sortPhotos(pics.filter((p) => p.folderPath === folderPath), getSort('p:' + folderPath, 'newest'));
    if (!ps.length) { box.appendChild(el('div.empty', 'No photos here.')); return; }
    box.appendChild(photoGrid(ps));
    if (PSEL.on) box.appendChild(photoSelBar(ps));
  });
}

function photoGrid(ps) {
  const grid = el('div.pgrid');
  ps.forEach((p, i) => {
    const tick = el('div.ptick', '✓');
    const cell = el('div.card.pcell' + (PSEL.paths.has(p.path) ? '.sel' : ''), {
      title: p.name,
      onclick: () => {
        if (PSEL.on) { if (PSEL.paths.has(p.path)) PSEL.paths.delete(p.path); else PSEL.paths.add(p.path); cell.classList.toggle('sel'); updateSelBar(); return; }
        go('photo', { list: ps, i });
      },
      oncontextmenu: (e) => photoMenu(e, ps, i),
    }, thumbImg(p), PSEL.on ? tick : null, el('div.name', p.name));
    grid.appendChild(cell);
  });
  return grid;
}

function photoSelBar(ps) {
  const n = el('b', '0');
  const bar = el('div.pselbar', n, el('span', ' selected'),
    el('button.btn', { onclick: () => { ps.forEach((p) => PSEL.paths.add(p.path)); refreshView(); } }, 'Select all'),
    el('button.btn', { onclick: () => deletePhotos(ps.filter((p) => PSEL.paths.has(p.path))) }, 'Delete'),
    el('button.btn.primary', { onclick: () => {
      const chosen = ps.filter((p) => PSEL.paths.has(p.path));
      if (chosen.length < 2 || chosen.length > 9) { toast('Choose 2 to 9 photos for a collage'); return; }
      PSEL.on = false; PSEL.paths.clear();
      go('collage', { photos: chosen.map((p) => p.path) });
    } }, '▦ Make a collage'));
  bar._n = n;
  setTimeout(updateSelBar, 0);
  return bar;
}
function updateSelBar() { const b = $('.pselbar'); if (b) b._n.textContent = String(PSEL.paths.size); }

function photoMenu(e, ps, i) {
  const p = ps[i];
  menu(e, [
    ['Open', () => go('photo', { list: ps, i })],
    ['Edit', () => go('peditor', { path: p.path })],
    ['Make a collage with it…', () => go('collage', { photos: [p.path] })],
    ['Show in folder', () => api.showInFolder(p.path)],
    '-',
    ['Delete…', () => deletePhotos([p])],
  ]);
}

async function deletePhotos(list) {
  if (!list.length) return;
  if (!await confirmBox(list.length === 1 ? 'Delete this photo?' : `Delete ${list.length} photos?`,
    'They go to the Recycle Bin, so you can get them back from there.', 'Delete')) return;
  let n = 0;
  for (const p of list) { if (await api.trashFile(p.path)) n++; }
  PICS = PICS ? PICS.filter((x) => !list.includes(x)) : null;
  PSEL.paths.clear();
  toast(plural(n, 'photo') + ' moved to the Recycle Bin');
  refreshView();
}

async function addPhotoFolder() {
  const r = await api.openDialog({ title: 'Choose a folder with photos', properties: ['openDirectory', 'multiSelections'] });
  if (!r || !r.length) return;
  for (const d of r) if (!L.photoDirs.includes(d)) L.photoDirs.push(d);
  save();
  PICS = null;
  refreshView();
}

async function openPhotoFiles() {
  const r = await api.openDialog({ title: 'Open photos', properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Photos', extensions: ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'avif', 'jfif'] }] });
  if (!r || !r.length) return;
  const list = await Promise.all(r.map(async (p) => ({ path: p, name: p.split(/[\\/]/).pop().replace(/\.[^.]+$/, ''), url: await api.fileUrl(p), folderPath: p.replace(/[\\/][^\\/]*$/, ''), size: 0, mtime: 0 })));
  go('photo', { list, i: 0 });
}

// ---------- the viewer ----------
const PV = { zoom: 1, x: 0, y: 0, rot: 0, show: null };

function photoView(arg) {
  const { list } = arg;
  let i = Math.max(0, Math.min(arg.i, list.length - 1));
  PV.zoom = 1; PV.x = 0; PV.y = 0; PV.rot = 0;
  const img = el('img.pvimg', { draggable: false, alt: '' });
  const stage = el('div.pvstage', img);
  const title = el('div.pvtitle');
  const counter = el('div.dim');
  const info = el('div.dim.pvinfo');
  const apply = () => { img.style.transform = `translate(${PV.x}px, ${PV.y}px) scale(${PV.zoom}) rotate(${PV.rot}deg)`; };
  const load = () => {
    const p = list[i];
    arg.i = i;
    PV.zoom = 1; PV.x = 0; PV.y = 0; PV.rot = 0; apply();
    title.textContent = p.name;
    counter.textContent = `${i + 1} of ${list.length}`;
    img.onload = () => { info.textContent = `${img.naturalWidth} × ${img.naturalHeight}` + (p.size ? ' · ' + fmtSize(p.size) : '') + (p.mtime ? ' · ' + new Date(p.mtime).toLocaleString() : ''); };
    img.src = p.url;
    renderTabBar();
  };
  const step = (d) => { if (list.length > 1) { i = (i + d + list.length) % list.length; load(); } };
  const zoomBy = (k, cx, cy) => {
    const r = stage.getBoundingClientRect();
    const ox = (cx ?? r.left + r.width / 2) - (r.left + r.width / 2), oy = (cy ?? r.top + r.height / 2) - (r.top + r.height / 2);
    const z = Math.max(1, Math.min(12, PV.zoom * k));
    PV.x = ox - (ox - PV.x) * (z / PV.zoom); PV.y = oy - (oy - PV.y) * (z / PV.zoom);
    PV.zoom = z;
    if (z === 1) { PV.x = 0; PV.y = 0; }
    apply();
  };
  stage.addEventListener('wheel', (e) => { e.preventDefault(); zoomBy(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.clientX, e.clientY); }, { passive: false });
  stage.addEventListener('dblclick', (e) => { if (PV.zoom > 1) { PV.zoom = 1; PV.x = 0; PV.y = 0; apply(); } else zoomBy(2.5, e.clientX, e.clientY); });
  let drag = null;
  stage.addEventListener('pointerdown', (e) => { if (PV.zoom > 1) { drag = { x: e.clientX - PV.x, y: e.clientY - PV.y }; stage.setPointerCapture(e.pointerId); } });
  stage.addEventListener('pointermove', (e) => { if (drag) { PV.x = e.clientX - drag.x; PV.y = e.clientY - drag.y; apply(); } });
  stage.addEventListener('pointerup', () => { drag = null; });
  const slideBtn = el('button.btn', { onclick: () => toggleShow() }, '▶ Slideshow');
  const toggleShow = () => {
    if (PV.show) { clearInterval(PV.show); PV.show = null; slideBtn.textContent = '▶ Slideshow'; return; }
    PV.show = setInterval(() => { if (NAV.view !== 'photo') { clearInterval(PV.show); PV.show = null; return; } step(1); }, 3500);
    slideBtn.textContent = '⏸ Stop slideshow';
  };
  const bar = el('div.pvbar',
    iconBtn('back', 'Back (Esc)', () => goBack()), el('div.pvtt', title, counter),
    el('button.btn', { title: 'Previous (←)', onclick: () => step(-1) }, '‹'), el('button.btn', { title: 'Next (→)', onclick: () => step(1) }, '›'),
    el('button.btn', { title: 'Zoom out (−)', onclick: () => zoomBy(1 / 1.25) }, '−'), el('button.btn', { title: 'Zoom in (+)', onclick: () => zoomBy(1.25) }, '+'),
    el('button.btn', { title: 'Turn (R) — only while viewing; use Edit to save it turned', onclick: () => { PV.rot = (PV.rot + 90) % 360; apply(); } }, '⟳'),
    slideBtn,
    el('button.btn', { onclick: () => go('peditor', { path: list[i].path }) }, '✏ Edit'),
    iconBtn('more', 'More', (e) => menu(e, [
      ['Make a collage with it…', () => go('collage', { photos: [list[i].path] })],
      ['Show in folder', () => api.showInFolder(list[i].path)],
      '-',
      ['Delete…', () => delCurrent()],
    ])));
  const delCurrent = async () => {
    const p = list[i];
    if (!await confirmBox('Delete this photo?', 'It goes to the Recycle Bin, so you can get it back from there.', 'Delete')) return;
    if (!await api.trashFile(p.path)) { toast('Couldn\'t delete it'); return; }
    if (PICS) PICS = PICS.filter((x) => x.path !== p.path);
    list.splice(i, 1);
    if (!list.length) goBack(); else { i = Math.min(i, list.length - 1); load(); }
  };
  main.appendChild(el('div.pview', bar, stage, info));
  PV.keys = (e) => {
    const k = e.key;
    if (k === 'ArrowRight' || k === 'PageDown') step(1);
    else if (k === 'ArrowLeft' || k === 'PageUp') step(-1);
    else if (k === 'Home') { i = 0; load(); }
    else if (k === 'End') { i = list.length - 1; load(); }
    else if (k === '+' || k === '=') zoomBy(1.25);
    else if (k === '-') zoomBy(1 / 1.25);
    else if (k === '0') { PV.zoom = 1; PV.x = 0; PV.y = 0; apply(); }
    else if (k === 'r' || k === 'R') { PV.rot = (PV.rot + 90) % 360; apply(); }
    else if (k === 'e' || k === 'E') go('peditor', { path: list[i].path });
    else if (k === ' ') toggleShow();
    else if (k === 'Delete') delCurrent();
    else if (k === 'Escape') goBack();
    else return false;
    e.preventDefault();
    return true;
  };
  load();
}
