// Tabs along the top, like File Explorer and Chrome: every tab is its own Echo window. Whatever you open
// in a tab (Home, Library, a folder, Videos, a video player, YouTube…) shows in that tab, with its name.
// + opens a new Home tab; right-click a tab to duplicate it or close others.
'use strict';

const TABS = { list: [], active: null, seq: 0 };

function makeTab(nav) {
  return { id: 't' + (++TABS.seq), nav: { view: nav.view, arg: nav.arg }, back: [], scroll: 0, pane: null, webTitle: '' };
}
const tabById = (id) => TABS.list.find((t) => t.id === id);
const activeTab = () => tabById(TABS.active);
const navOf = (t) => (t.id === TABS.active ? NAV : t.nav);

function tabTitle(t) {
  const n = navOf(t);
  const a = n.arg;
  switch (n.view) {
    case 'home': return 'Home';
    case 'search': return 'Search';
    case 'library': return 'Library';
    case 'songs': return 'Songs';
    case 'folder': case 'youtube': return (playlist(a) || {}).name || 'Folder';
    case 'videos': return a && a.folder ? a.folder.split(/[\\/]/).pop() : 'Videos';
    case 'veditor': return 'Video editor';
    case 'aeditor': return 'Audio editor';
    case 'ytweb': return t.webTitle || 'YouTube';
    case 'video': return (a && a.list && a.list[a.i] && a.list[a.i].name) || 'Video';
    default: return 'Echo';
  }
}
const TAB_ICONS = { home: 'home', search: 'search', library: 'library', songs: 'music', folder: 'folder', youtube: 'yt', ytweb: 'yt',
  videos: 'movie', video: 'movie', veditor: 'cut', aeditor: 'wave' };

function renderTabBar() {
  const bar = $('#tabbar');
  if (!bar) return;
  bar.innerHTML = '';
  const cur = activeTab();
  const canBack = cur && (cur.back.length > 0 || NAV.view !== 'home');
  bar.appendChild(el('button.tabback' + (canBack ? '' : '.dis'), { title: 'Back (Alt+←)', onclick: () => { if (canBack) goBack(); } }, icon('back')));
  for (const t of TABS.list) {
    const view = navOf(t).view;
    const node = el('div.tab' + (t.id === TABS.active ? '.on' : '') + (view === 'ytweb' || view === 'youtube' ? '.k-yt' : ''), {
      title: tabTitle(t),
      onmousedown: (e) => { if (e.button === 0) switchTab(t.id); },
      onauxclick: (e) => { if (e.button === 1) { e.preventDefault(); closeTab(t.id); } },
      oncontextmenu: (e) => tabMenu(e, t),
    }, icon(TAB_ICONS[view] || 'music'), el('span.tt', tabTitle(t)),
    el('button.tx', { title: 'Close tab (Ctrl+W)', onmousedown: (e) => e.stopPropagation(), onclick: (e) => { e.stopPropagation(); closeTab(t.id); } }, icon('close')));
    bar.appendChild(node);
  }
  bar.appendChild(el('button.tabnew', { title: 'New tab (Ctrl+T)', onclick: () => newTab() }, icon('add')));
  $$('#side .nav[data-go]').forEach((n) => n.classList.toggle('on', n.dataset.go === NAV.view));
}

function tabMenu(e, t) {
  const i = TABS.list.indexOf(t);
  menu(e, [
    ['New tab', () => newTab()],
    ['Duplicate', () => duplicateTab(t)],
    '-',
    ['Close', () => closeTab(t.id)],
    TABS.list.length > 1 ? ['Close other tabs', () => TABS.list.filter((x) => x !== t).forEach((x) => closeTab(x.id))] : null,
    i < TABS.list.length - 1 ? ['Close tabs to the right', () => TABS.list.slice(i + 1).forEach((x) => closeTab(x.id))] : null,
  ]);
}

/** Where a tab is now (for Back and Duplicate). */
function currentNav(t) {
  if (t.id !== TABS.active) return { view: t.nav.view, arg: t.nav.arg };
  if (NAV.view === 'video' && V.list && V.list.length) return { view: 'video', arg: { list: V.list, i: V.i } };
  if (NAV.view === 'ytweb' && t.pane) {
    const wv = $('webview', t.pane);
    try { return { view: 'ytweb', arg: wv.getURL() || NAV.arg }; } catch (e) { /* not loaded yet */ }
  }
  return { view: NAV.view, arg: NAV.arg };
}

/** Takes down what only this page needs (the video player, the YouTube page). */
function teardown(t) {
  if (NAV.view === 'video') unmountVideo(true);
  if (t.pane) { t.pane.remove(); t.pane = null; t.webTitle = ''; }
}

/** Shows the active tab's page. */
function showPage(t) {
  if (NAV.view === 'ytweb') {
    main.innerHTML = '';
    if (!t.pane) {
      t.pane = buildYtPane(NAV.arg || 'https://www.youtube.com/', t);
      $('#content').appendChild(t.pane);
    }
    t.pane.classList.remove('off');
  } else if (NAV.view === 'video') {
    main.innerHTML = '';
    mountVideo(NAV.arg.list, NAV.arg.i);
  } else render();
  renderTabBar();
}

/** Opens a page in the current tab (what go() does). */
function tabNavigate(view, arg) {
  const t = activeTab();
  const here = currentNav(t);
  here.scroll = main.scrollTop; // so Back returns to the same place in a long list
  if (!(here.view === view && (here.arg ?? null) === (arg ?? null))) t.back.push(here);
  if (t.back.length > 30) t.back.shift();
  teardown(t);
  NAV.view = view; NAV.arg = arg;
  showPage(t);
}

/** Back to the previous page in this tab (closing a video player does this). */
function tabBack() {
  const t = activeTab();
  const prev = t.back.pop() || { view: 'home', arg: null };
  teardown(t);
  NAV.view = prev.view; NAV.arg = prev.arg;
  clearSelection(true);
  showPage(t);
  restoreScroll(prev.scroll || 0);
}

/** The ← button: back to the previous page in this tab (out of a video player too). */
function goBack() {
  if (NAV.view === 'video') closeVideo(true);
  else tabBack();
}

// the mouse's side "back" button
document.addEventListener('mouseup', (e) => { if (e.button === 3) { e.preventDefault(); goBack(); } });

/** Scrolls back to where you were. Some pages (like Videos) fill in a moment later, so it keeps trying briefly. */
function restoreScroll(y) {
  let n = 0;
  const view = NAV.view;
  const f = () => {
    if (NAV.view !== view) return;
    main.scrollTop = y;
    if (Math.abs(main.scrollTop - y) > 2 && n++ < 90) requestAnimationFrame(f);
  };
  f();
}

function switchTab(id) {
  if (id === TABS.active || !tabById(id)) return;
  const cur = activeTab();
  if (cur) {
    cur.scroll = main.scrollTop;
    cur.nav = currentNav(cur);
    if (NAV.view === 'video') unmountVideo(true); // it continues from the same moment when you come back
    if (cur.pane) cur.pane.classList.add('off');
    clearSelection(true);
  }
  TABS.active = id;
  const t = activeTab();
  NAV.view = t.nav.view; NAV.arg = t.nav.arg;
  showPage(t);
  restoreScroll(t.scroll);
}

/** A new tab (Home unless told otherwise), next to the current one. */
function newTab(nav) {
  const t = makeTab(nav || { view: 'home', arg: null });
  const i = TABS.list.indexOf(activeTab());
  TABS.list.splice(i < 0 ? TABS.list.length : i + 1, 0, t);
  switchTab(t.id);
  return t;
}

function duplicateTab(t) {
  const nav = currentNav(t);
  const back = t.back.slice();
  const copy = newTab(nav);
  copy.back = back;
  return copy;
}

function closeTab(id) {
  const t = tabById(id);
  if (!t) return;
  if (TABS.list.length === 1) { // the last tab: go Home instead, Echo stays open
    teardown(t);
    t.back = [];
    NAV.view = 'home'; NAV.arg = null;
    showPage(t);
    return;
  }
  const i = TABS.list.indexOf(t);
  if (TABS.active === id) {
    if (NAV.view === 'video') { if (V.savePos) V.savePos(); unmountVideo(true); }
    if (t.pane) t.pane.remove();
    TABS.list.splice(i, 1);
    const next = TABS.list[Math.min(i, TABS.list.length - 1)];
    TABS.active = next.id;
    NAV.view = next.nav.view; NAV.arg = next.nav.arg;
    showPage(next);
    main.scrollTop = next.scroll;
  } else {
    if (t.pane) t.pane.remove();
    TABS.list.splice(i, 1);
    renderTabBar();
  }
}

/** A video opens in the current tab, and the tab takes its name. */
function openVideo(list, i) { go('video', { list, i }); }

/** Closing the player (← or Esc) goes back to where you were in this tab. */
function closeVideo(silent) {
  if (NAV.view !== 'video') { unmountVideo(silent); return; }
  if (V.savePos) V.savePos();
  const v = vid();
  if (L.prefs.videoBackground && v && !v.paused && !silent) { playVideoAsAudio(); return; }
  tabBack();
}

/** The player moved to another video in the list: the tab's name follows. */
function onVideoItem() {
  if (NAV.view === 'video') { NAV.arg = { list: V.list, i: V.i }; renderTabBar(); }
}

function cycleTab(d) {
  const i = TABS.list.indexOf(activeTab());
  switchTab(TABS.list[(i + d + TABS.list.length) % TABS.list.length].id);
}

// the first tab
(() => { const t = makeTab({ view: 'home', arg: null }); TABS.list.push(t); TABS.active = t.id; })();

// links YouTube opens "in a new tab" (Ctrl+click, middle-click) become Echo tabs
api.onWebNewTab((url) => newTab({ view: 'ytweb', arg: url }));

document.addEventListener('keydown', (e) => {
  if (e.altKey && e.key === 'ArrowLeft' && NAV.view !== 'ytweb') { e.preventDefault(); goBack(); return; }
  if (!(e.ctrlKey || e.metaKey)) return;
  const k = e.key.toLowerCase();
  if (k === 't') { e.preventDefault(); e.stopImmediatePropagation(); newTab(); }
  else if (k === 'w') { e.preventDefault(); e.stopImmediatePropagation(); closeTab(TABS.active); }
  else if (k === 'tab') { e.preventDefault(); e.stopImmediatePropagation(); cycleTab(e.shiftKey ? -1 : 1); }
}, true);

// ---------- the window's ✕: ask what to close ----------
let closeAsking = false;
api.onCloseAsk(async () => {
  if (closeAsking) return;
  closeAsking = true;
  try {
    const t = activeTab();
    const opts = [];
    if (NAV.view === 'video') opts.push(['🎬 Close this video — ' + tabTitle(t), () => closeVideo(true)]);
    if (TABS.list.length > 1) {
      opts.push(['🗂️ Close this tab — ' + tabTitle(t), () => closeTab(t.id)]);
      opts.push([`🗂️ Close the other ${TABS.list.length - 1} tab${TABS.list.length > 2 ? 's' : ''}`, () => TABS.list.filter((x) => x !== t).forEach((x) => closeTab(x.id))]);
    }
    if (!$('#player').classList.contains('off')) opts.push(['🎵 Stop the music — ' + ($('#pTitle').textContent || 'song'), () => closePlayer()]);
    opts.push(['➖ Minimize Echo (music keeps playing)', () => api.minimizeApp()]);
    opts.push(['⏻ Quit Echo completely', () => quitEcho()]);
    const i = await choose('What do you want to close?', opts.map((o) => o[0]));
    if (i >= 0) opts[i][1]();
  } finally {
    closeAsking = false;
  }
});

function quitEcho() {
  try { if (NAV.view === 'video' && V.savePos) V.savePos(); } catch (e) { /* ignore */ }
  try { api.saveLibrary(L); } catch (e) { /* ignore */ }
  setTimeout(() => api.quitApp(), 150);
}
