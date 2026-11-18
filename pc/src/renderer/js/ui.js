// Small helpers used by every screen: elements, dialogs, menus, messages.
'use strict';

const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => Array.from(root.querySelectorAll(s));

/** el('div.class#id', {attrs}, children...) */
function el(spec, attrs, ...kids) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(spec) || [];
  const node = document.createElement(m[1] || 'div');
  (m[2] || '').replace(/([.#])([\w-]+)/g, (_, t, v) => { if (t === '.') node.classList.add(v); else node.id = v; });
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
      else if (k === 'html') node.innerHTML = v;
      else node.setAttribute(k, v === true ? '' : v);
    }
  }
  for (const k of kids.flat()) {
    if (k == null || k === false) continue;
    node.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return node;
}

function icon(name, cls) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  if (cls) s.setAttribute('class', cls);
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', '#i-' + name);
  s.appendChild(u);
  return s;
}

function iconBtn(name, title, onclick) {
  return el('button.icon-btn', { title, onclick }, icon(name));
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fmtTime(ms) {
  if (!ms || ms < 0 || !isFinite(ms)) return '0:00';
  let s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60); s %= 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

function fmtSize(b) {
  if (b > 1073741824) return (b / 1073741824).toFixed(1) + ' GB';
  return Math.max(1, Math.round(b / 1048576)) + ' MB';
}

function plural(n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); }

const GRADS = [['#00d2ff', '#3a7bd5'], ['#f72585', '#7209b7'], ['#4cc9f0', '#4361ee'], ['#ffb703', '#fb8500'],
  ['#ff4d6d', '#c9184a'], ['#06d6a0', '#118ab2'], ['#c77dff', '#7b2cbf'], ['#ff9e00', '#ff5400']];
function hash(s) { let h = 0; for (const c of String(s || '')) h = (h * 31 + c.codePointAt(0)) | 0; return Math.abs(h); }
function grad(key) { const g = GRADS[hash(key) % GRADS.length]; return `linear-gradient(135deg, ${g[0]}, ${g[1]})`; }
const YT_GRAD = 'linear-gradient(135deg, #ff4d6d, #c9184a)';
function firstLetter(s) { const t = String(s || '').trim(); return t ? Array.from(t)[0].toUpperCase() : '♪'; }

// ---------- messages ----------
let toastTimer = null;
function toast(msg, ms = 2600) {
  let t = $('#toast');
  if (!t) { t = el('div#toast'); document.body.appendChild(t); }
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
}

// ---------- dialogs ----------
const modalStack = [];
function closeModal() {
  const m = modalStack.pop();
  if (m) m.remove();
}

/**
 * dialog({ title, body: string|Node, buttons: [{ text, primary, danger, value, keep }], wide })
 * Resolves with the clicked button's value (or null when closed).
 */
function dialog(o) {
  return new Promise((resolve) => {
    const wrap = el('div#modal');
    const box = el('div.dialog' + (o.wide ? '.wide' : ''));
    if (o.title) box.appendChild(el('h3', o.title));
    const body = el('div.body');
    if (o.body instanceof Node) body.appendChild(o.body); else if (o.body) body.innerHTML = o.body;
    box.appendChild(body);
    const btns = el('div.btns');
    const finish = (v) => { if (modalStack.includes(wrap)) { modalStack.splice(modalStack.indexOf(wrap), 1); wrap.remove(); } resolve(v); };
    for (const b of (o.buttons || [{ text: 'OK', primary: true, value: true }])) {
      btns.appendChild(el('button.btn' + (b.primary ? '.primary' : '') + (b.danger ? '.danger' : ''), {
        onclick: async () => {
          if (b.check && !(await b.check())) return;
          finish(b.value !== undefined ? b.value : b.text);
        },
      }, b.text));
    }
    box.appendChild(btns);
    wrap.appendChild(box);
    wrap.addEventListener('mousedown', (e) => { if (e.target === wrap && !o.modal) finish(null); });
    wrap._finish = finish;
    document.body.appendChild(wrap);
    modalStack.push(wrap);
    const first = box.querySelector('input,textarea');
    if (first) setTimeout(() => { first.focus(); if (first.select) first.select(); }, 30);
  });
}

function alertBox(title, body) { return dialog({ title, body: esc(body).replace(/\n/g, '<br>'), buttons: [{ text: 'OK', primary: true }] }); }

function confirmBox(title, body, yes = 'OK', danger = false) {
  return dialog({ title, body: esc(body).replace(/\n/g, '<br>'),
    buttons: [{ text: 'Cancel', value: false }, { text: yes, primary: !danger, danger, value: true }] });
}

async function prompt(title, def = '', placeholder = '', help = '') {
  const input = el('input', { type: 'text', value: def, placeholder });
  const body = el('div', help ? el('div', { html: help }) : null, input);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { const m = modalStack[modalStack.length - 1]; if (m) m._finish(true); } });
  const ok = await dialog({ title, body, buttons: [{ text: 'Cancel', value: false }, { text: 'OK', primary: true, value: true }] });
  return ok ? input.value.trim() : null;
}

/** Pick one of several options. Resolves with the index or -1. */
function choose(title, options, help) {
  return new Promise((resolve) => {
    const body = el('div');
    if (help) body.appendChild(el('div', { html: help, style: { marginBottom: '8px' } }));
    let done = false;
    options.forEach((o, i) => body.appendChild(el('div.opt', { onclick: () => { done = true; closeTop(); resolve(i); } }, o)));
    dialog({ title, body, buttons: [{ text: 'Cancel', value: null }] }).then(() => { if (!done) resolve(-1); });
  });
}

function closeTop() {
  const m = modalStack[modalStack.length - 1];
  if (m) m._finish(null);
}

/** A slider row inside a dialog. Returns the <input>. */
function sliderRow(parent, label, min, max, value, unit = '', step = 1) {
  const val = el('span', value + unit);
  const r = el('input', { type: 'range', min, max, step, value });
  r.addEventListener('input', () => { val.textContent = r.value + unit; });
  parent.appendChild(el('div.label', el('span', label), val));
  parent.appendChild(r);
  return r;
}

// ---------- right-click / ⋮ menus ----------
function menu(e, items) {
  e.preventDefault();
  e.stopPropagation();
  closeMenu();
  const m = el('div#menu');
  for (const it of items) {
    if (!it) continue;
    if (it === '-') { m.appendChild(el('div.sep')); continue; }
    m.appendChild(el('div.mi', { onclick: () => { closeMenu(); it[1](); } }, it[0]));
  }
  document.body.appendChild(m);
  const x = e.clientX || (e.target.getBoundingClientRect().left);
  const y = e.clientY || (e.target.getBoundingClientRect().bottom);
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, innerWidth - r.width - 8) + 'px';
  m.style.top = Math.min(y, innerHeight - r.height - 8) + 'px';
}
function closeMenu() { const m = $('#menu'); if (m) m.remove(); }
document.addEventListener('mousedown', (e) => { if (!e.target.closest('#menu')) closeMenu(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeMenu(); } });

/** Keeps the coloured part of a range input in step with its value. */
function paintRange(r) {
  const p = (r.value - r.min) / ((r.max - r.min) || 1) * 100;
  r.style.setProperty('--p', p + '%');
}

/** Progress dialog with a bar. Returns { set(pct, text), close() } and a cancel callback. */
function progressBox(title, onCancel) {
  const txt = el('div', 'Starting…');
  const bar = el('div.bar', el('i'));
  const body = el('div', txt, bar);
  let closed = false;
  dialog({ title, body, modal: true, buttons: onCancel ? [{ text: 'Cancel', value: 'cancel' }] : [] }).then((v) => {
    if (v === 'cancel' && onCancel && !closed) onCancel();
  });
  return {
    set(pct, text) { bar.firstChild.style.width = pct + '%'; if (text) txt.textContent = text; },
    close() { closed = true; if (body.closest('#modal')) body.closest('#modal')._finish(null); },
  };
}
