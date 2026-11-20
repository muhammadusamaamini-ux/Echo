// Tabs along the top, like Chrome (and like Book Shelf's open books): the Echo tab (library, editors…),
// a tab for every YouTube page and a tab for every video player. Switching keeps each tab as you left it.
'use strict';

const TABS = { list: [{ id: 'echo', kind: 'echo', title: 'Echo' }], active: 'echo', seq: 0, history: ['echo'] };

const tabById = (id) => TABS.list.find((t) => t.id === id);
const activeTab = () => tabById(TABS.active);

function renderTabBar() {
  const bar = $('#tabbar');
  if (!bar) return;
  bar.innerHTML = '';
  for (const t of TABS.list) {
    const ic = t.kind === 'yt' ? icon('yt') : t.kind === 'video' ? icon('movie') : el('img', { src: 'icon.png', alt: '' });
    const node = el('div.tab' + (t.id === TABS.active ? '.on' : '') + '.k-' + t.kind, {
      title: t.title,
      onclick: () => switchTab(t.id),
      onauxclick: (e) => { if (e.button === 1 && t.kind !== 'echo') { e.preventDefault(); closeTab(t.id); } },
    }, ic, el('span.tt', t.kind === 'echo' ? 'Echo' : t.title || (t.kind === 'yt' ? 'YouTube' : 'Video')));
    if (t.kind !== 'echo') node.appendChild(el('button.tx', { title: 'Close tab (Ctrl+W)', onclick: (e) => { e.stopPropagation(); closeTab(t.id); } }, icon('close')));
    bar.appendChild(node);
  }
  bar.appendChild(el('button.tabnew', { title: 'New YouTube tab (Ctrl+T)', onclick: () => newYtTab() }, icon('add')));
  // the sidebar shows where you are
  const t = activeTab();
  $$('#side .nav[data-go]').forEach((n) => n.classList.toggle('on', t.kind === 'yt' ? n.dataset.go === 'ytweb' : t.kind === 'echo' && n.dataset.go === NAV.view));
}

function leaveTab(t) {
  if (!t) return;
  if (t.kind === 'video') {
    if (V.list && V.list.length) { t.list = V.list; t.i = V.i; }
    unmountVideo(true); // remembers where you were; it continues from there when you come back
  } else if (t.kind === 'yt' && t.pane) t.pane.classList.add('off');
}

function enterTab(t) {
  if (t.kind === 'video') mountVideo(t.list, t.i);
  else if (t.kind === 'yt' && t.pane) t.pane.classList.remove('off');
}

function switchTab(id) {
  const t = tabById(id);
  if (!t) return;
  if (id !== TABS.active) {
    leaveTab(activeTab());
    TABS.active = id;
    TABS.history = TABS.history.filter((x) => x !== id).concat(id);
    enterTab(t);
  }
  renderTabBar();
}

function closeTab(id) {
  const t = tabById(id);
  if (!t || t.kind === 'echo') return;
  if (TABS.active === id) {
    if (t.kind === 'video') unmountVideo(true);
  }
  if (t.pane) t.pane.remove(); // the YouTube page goes away with it
  TABS.list.splice(TABS.list.indexOf(t), 1);
  TABS.history = TABS.history.filter((x) => x !== id);
  if (TABS.active === id) {
    TABS.active = null;
    const back = TABS.history[TABS.history.length - 1] || 'echo';
    TABS.active = back;
    enterTab(tabById(back));
  }
  renderTabBar();
}

function addTab(t) {
  t.id = 't' + (++TABS.seq);
  const i = TABS.list.indexOf(activeTab());
  TABS.list.splice(i + 1, 0, t); // opens next to the current tab, like Chrome
  switchTab(t.id);
  return t;
}

/** A new YouTube tab (url optional). */
function newYtTab(url) {
  const t = { kind: 'yt', title: 'YouTube' };
  t.pane = buildYtPane(url || 'https://www.youtube.com/', t);
  t.pane.classList.add('off');
  $('#content').appendChild(t.pane);
  return addTab(t);
}

/** Sidebar → YouTube: back to your last YouTube tab, or a new one. */
function focusYouTube() {
  const last = [...TABS.history].reverse().map(tabById).find((t) => t && t.kind === 'yt');
  if (last) switchTab(last.id); else newYtTab();
}

/** Every video you open gets its own tab. */
function openVideo(list, i) {
  return addTab({ kind: 'video', list, i, title: (list[i] && list[i].name) || 'Video' });
}

/** Closing the player (← or Esc) closes its tab. */
function closeVideo(silent) {
  const t = activeTab();
  if (t && t.kind === 'video') {
    if (V.savePos) V.savePos();
    const v = vid();
    if (L.prefs.videoBackground && v && !v.paused && !silent) { playVideoAsAudio(); return; }
    closeTab(t.id);
  } else unmountVideo(silent);
}

/** The player moved to another video in the list: the tab's name follows. */
function onVideoItem(item) {
  const t = activeTab();
  if (t && t.kind === 'video') { t.title = item.name; t.list = V.list; t.i = V.i; renderTabBar(); }
}

function cycleTab(d) {
  const i = TABS.list.indexOf(activeTab());
  switchTab(TABS.list[(i + d + TABS.list.length) % TABS.list.length].id);
}

// links that YouTube opens "in a new tab" (Ctrl+click, middle-click) become Echo tabs
api.onWebNewTab((url) => newYtTab(url));

document.addEventListener('keydown', (e) => {
  if (!(e.ctrlKey || e.metaKey)) return;
  if (e.key === 't' || e.key === 'T') { e.preventDefault(); e.stopImmediatePropagation(); newYtTab(); }
  else if (e.key === 'w' || e.key === 'W') { e.preventDefault(); e.stopImmediatePropagation(); closeTab(TABS.active); }
  else if (e.key === 'Tab') { e.preventDefault(); e.stopImmediatePropagation(); cycleTab(e.shiftKey ? -1 : 1); }
}, true);

// ---------- the window's ✕: ask what to close ----------
let closeAsking = false;
api.onCloseAsk(async () => {
  if (closeAsking) return;
  closeAsking = true;
  try {
    const t = activeTab();
    const opts = [];
    if (t.kind !== 'echo') opts.push([(t.kind === 'yt' ? '▶️ Close this YouTube tab' : '🎬 Close this video') + ' — ' + t.title, () => closeVideoOrTab(t)]);
    const others = TABS.list.filter((x) => x.kind !== 'echo');
    if (others.length > 1 || (others.length === 1 && others[0] !== t)) opts.push([`🗂️ Close all ${others.length} tabs (keep Echo open)`, () => others.forEach((x) => closeTab(x.id))]);
    if (!$('#player').classList.contains('off')) opts.push(['🎵 Stop the music — ' + ($('#pTitle').textContent || 'song'), () => closePlayer()]);
    opts.push(['➖ Minimize Echo (music keeps playing)', () => api.minimizeApp()]);
    opts.push(['⏻ Quit Echo completely', () => quitEcho()]);
    const i = await choose('What do you want to close?', opts.map((o) => o[0]));
    if (i >= 0) opts[i][1]();
  } finally {
    closeAsking = false;
  }
});

function closeVideoOrTab(t) {
  if (t.kind === 'video' && TABS.active === t.id) closeVideo(true);
  else closeTab(t.id);
}

function quitEcho() {
  try { if (typeof V !== 'undefined' && V.savePos && $('#vplayer')) V.savePos(); } catch (e) { /* ignore */ }
  try { api.saveLibrary(L); } catch (e) { /* ignore */ }
  setTimeout(() => api.quitApp(), 150);
}
