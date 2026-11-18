// The music player: queue, shuffle, repeat, the bottom bar, Now Playing, and Windows media keys.
'use strict';

const audio = $('#audio');
const P = { queue: [], order: [], pos: -1, shuffle: false, repeat: 'off', song: null, artUrl: null };

function currentSong() { return P.song; }

function buildOrder(start) {
  P.order = P.queue.map((_, i) => i);
  if (P.shuffle) {
    for (let i = P.order.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [P.order[i], P.order[j]] = [P.order[j], P.order[i]]; }
    const k = P.order.indexOf(start);
    if (k > 0) { P.order.splice(k, 1); P.order.unshift(start); }
    P.pos = 0;
  } else P.pos = Math.max(0, start);
}

/** Plays a list of songs starting at index (or a random one with shuffle). */
function playList(list, index = 0, shuffle = null) {
  list = list.filter(Boolean);
  if (!list.length) { toast('Nothing to play'); return; }
  if (shuffle != null) P.shuffle = shuffle;
  P.queue = list.slice();
  const start = shuffle ? Math.floor(Math.random() * list.length) : Math.min(index, list.length - 1);
  buildOrder(start);
  loadCurrent(true);
}

function playNextSong(s) {
  if (!P.song) { playList([s], 0); return; }
  P.queue.push(s);
  P.order.splice(P.pos + 1, 0, P.queue.length - 1);
  toast('Plays next: ' + s.title);
  renderQueue();
}
function addToQueue(list) {
  if (!P.song) { playList(list, 0); return; }
  for (const s of list) { P.queue.push(s); P.order.push(P.queue.length - 1); }
  toast(plural(list.length, 'song') + ' added to the queue');
  renderQueue();
}

async function loadCurrent(play, at = 0) {
  const s = P.queue[P.order[P.pos]];
  if (!s) return;
  P.song = s;
  audio.src = s.uri;
  if (at) audio.currentTime = at / 1000;
  if (play) audio.play().catch(() => {});
  $('#player').classList.remove('off');
  document.documentElement.style.setProperty('--pbh', $('#player').offsetHeight + 'px');
  $('#pTitle').textContent = s.title;
  $('#pArtist').textContent = songSubtitle(s);
  P.artUrl = null;
  setArt($('#pArt'), s, null);
  if (s.source === 'file' && s.path) {
    const url = await api.art(s.path);
    if (P.song === s && url) { P.artUrl = url; setArt($('#pArt'), s, url); updateNowPlaying(); updateSession(); }
  }
  updateSession();
  updateNowPlaying();
  markPlaying();
  saveState();
}

function setArt(node, s, url) {
  node.innerHTML = '';
  if (url) { node.style.background = `center/cover url("${url}")`; return; }
  node.style.background = grad(s.title);
  node.appendChild(icon('music'));
}

function next(auto) {
  if (!P.queue.length) return;
  if (auto && P.repeat === 'one') { audio.currentTime = 0; audio.play(); return; }
  if (P.pos < P.order.length - 1) P.pos++;
  else if (P.repeat === 'all' || !auto) { if (P.shuffle) buildOrder(P.order[0]); P.pos = 0; }
  else { audio.pause(); return; }
  loadCurrent(true);
}
function prev() {
  if (audio.currentTime > 3 || P.pos === 0) { audio.currentTime = 0; return; }
  P.pos--;
  loadCurrent(true);
}
function togglePlay() {
  if (!P.song) return;
  if (audio.paused) audio.play().catch(() => {}); else audio.pause();
}
function closePlayer() {
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  P.queue = []; P.order = []; P.pos = -1; P.song = null;
  $('#player').classList.add('off');
  $('#nowplaying') && $('#nowplaying').remove();
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null;
  delete L.prefs.lastQueue;
  save();
  markPlaying();
}

// ---------- bar ----------
let seeking = false;
function updateBar() {
  const d = audio.duration * 1000, t = audio.currentTime * 1000;
  $('#pPos').textContent = fmtTime(t);
  $('#pDur').textContent = fmtTime(d);
  if (!seeking) { const r = $('#pSeek'); r.value = d ? Math.round(t / d * 1000) : 0; paintRange(r); }
  const npSeek = $('#npSeek');
  if (npSeek && !seeking) { npSeek.value = d ? Math.round(t / d * 1000) : 0; paintRange(npSeek); $('#npPos').textContent = fmtTime(t); $('#npDur').textContent = fmtTime(d); }
}
function updateButtons() {
  const playing = !audio.paused;
  $('#pPlay use').setAttribute('href', playing ? '#i-pause' : '#i-play');
  const np = $('#npPlay use');
  if (np) np.setAttribute('href', playing ? '#i-pause' : '#i-play');
  $('#pShuffle').classList.toggle('on', P.shuffle);
  $('#pRepeat').classList.toggle('on', P.repeat !== 'off');
  $('#pRepeat use').setAttribute('href', P.repeat === 'one' ? '#i-repeat1' : '#i-repeat');
  $('#pMute use').setAttribute('href', audio.muted || audio.volume === 0 ? '#i-mute' : '#i-vol');
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
}
function toggleShuffle() {
  P.shuffle = !P.shuffle;
  if (P.song) { const cur = P.order[P.pos]; buildOrder(cur); }
  toast(P.shuffle ? 'Shuffle on' : 'Shuffle off');
  updateButtons();
  renderQueue();
}
function cycleRepeat() {
  P.repeat = P.repeat === 'off' ? 'all' : P.repeat === 'all' ? 'one' : 'off';
  toast({ off: 'Repeat off', all: 'Repeat all', one: 'Repeat this song' }[P.repeat]);
  updateButtons();
}

function markPlaying() {
  const id = P.song && P.song.id;
  $$('[data-song]').forEach((n) => n.classList.toggle('playing', n.dataset.song === id));
}

// ---------- Now Playing (big) ----------
function openNowPlaying() {
  if (!P.song) return;
  if ($('#nowplaying')) { $('#nowplaying').remove(); return; }
  const v = el('div#nowplaying',
    el('button.icon-btn.close', { title: 'Close (Esc)', onclick: () => v.remove() }, icon('close')),
    el('div.big#npArt'),
    el('div.info',
      el('div.dim', 'NOW PLAYING'),
      el('h1#npTitle'), el('div.sub#npArtist', { style: { fontSize: '18px' } }),
      el('div.seek', { style: { marginTop: '24px' } }, el('span#npPos', '0:00'),
        el('input#npSeek.fill', { type: 'range', min: 0, max: 1000, value: 0 }), el('span#npDur', '0:00')),
      el('div.row', { style: { marginTop: '14px', justifyContent: 'center' } },
        iconBtn('shuffle', 'Shuffle', toggleShuffle), iconBtn('prev', 'Previous', prev),
        el('button.play#npPlay', { onclick: togglePlay, style: { width: '58px', height: '58px' } }, icon('play')),
        iconBtn('next', 'Next', () => next(false)), iconBtn('repeat', 'Repeat', cycleRepeat)),
      el('h2', 'Up next'), el('div.queue#npQueue')));
  document.body.appendChild(v);
  const r = $('#npSeek');
  r.addEventListener('input', () => { seeking = true; paintRange(r); $('#npPos').textContent = fmtTime(r.value / 1000 * audio.duration * 1000); });
  r.addEventListener('change', () => { audio.currentTime = r.value / 1000 * audio.duration; seeking = false; });
  updateNowPlaying();
  updateButtons();
}
function updateNowPlaying() {
  if (!$('#nowplaying') || !P.song) return;
  $('#npTitle').textContent = P.song.title;
  $('#npArtist').textContent = songSubtitle(P.song) + (P.song.album ? ' · ' + P.song.album : '');
  const a = $('#npArt');
  a.innerHTML = '';
  if (P.artUrl) a.style.background = `center/cover url("${P.artUrl}")`;
  else { a.style.background = grad(P.song.title); a.appendChild(icon('music')); }
  renderQueue();
}
function renderQueue() {
  const q = $('#npQueue');
  if (!q) return;
  q.innerHTML = '';
  P.order.forEach((qi, k) => {
    if (k < P.pos) return;
    const s = P.queue[qi];
    q.appendChild(el('div.item' + (k === P.pos ? '.playing' : ''), {
      style: { gridTemplateColumns: '36px 1fr 70px' },
      ondblclick: () => { P.pos = k; loadCurrent(true); },
      onclick: () => { P.pos = k; loadCurrent(true); },
    }, el('div.num', k === P.pos ? '▶' : String(k - P.pos)),
      el('div', el('div.title', s.title), el('div.s', songSubtitle(s))),
      el('div.dur', fmtTime(s.duration))));
  });
}

// ---------- Windows media keys / overlay ----------
function updateSession() {
  if (!('mediaSession' in navigator) || !P.song) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: P.song.title, artist: songSubtitle(P.song), album: P.song.album || 'Echo',
    artwork: P.artUrl ? [{ src: P.artUrl, sizes: '512x512' }] : [],
  });
}
if ('mediaSession' in navigator) {
  const ms = navigator.mediaSession;
  ms.setActionHandler('play', () => audio.play());
  ms.setActionHandler('pause', () => audio.pause());
  ms.setActionHandler('nexttrack', () => next(false));
  ms.setActionHandler('previoustrack', prev);
  try { ms.setActionHandler('seekto', (d) => { audio.currentTime = d.seekTime; }); } catch (e) { /* older */ }
}

// ---------- remember what was playing ----------
let stateTimer = null;
function saveState() {
  clearTimeout(stateTimer);
  stateTimer = setTimeout(() => {
    if (!P.song) return;
    L.prefs.lastQueue = { ids: P.queue.map((s) => s.id), order: P.order, pos: P.pos, at: Math.round(audio.currentTime * 1000),
      shuffle: P.shuffle, repeat: P.repeat };
    save();
  }, 1500);
}
function restoreState() {
  const q = L.prefs.lastQueue;
  if (!q || !q.ids) return;
  const list = q.ids.map(song).filter(Boolean);
  if (!list.length || list.length !== q.ids.length) return;
  P.queue = list; P.order = q.order || list.map((_, i) => i); P.pos = Math.min(q.pos || 0, P.order.length - 1);
  P.shuffle = !!q.shuffle; P.repeat = q.repeat || 'off';
  loadCurrent(false, q.at || 0);
  updateButtons();
}

// ---------- wiring ----------
audio.volume = L.prefs.volume == null ? 0.8 : L.prefs.volume;
audio.addEventListener('timeupdate', () => { updateBar(); if (Math.random() < 0.05) saveState(); });
audio.addEventListener('loadedmetadata', () => {
  updateBar();
  if (P.song && !P.song.duration && isFinite(audio.duration)) { P.song.duration = Math.round(audio.duration * 1000); save(); }
});
audio.addEventListener('play', updateButtons);
audio.addEventListener('pause', () => { updateButtons(); saveState(); });
audio.addEventListener('ended', () => next(true));
audio.addEventListener('error', () => {
  if (!P.song) return;
  toast(`Can't play "${P.song.title}"` + (P.song.source === 'drive' ? ' (check your internet)' : ''));
  setTimeout(() => { if (P.queue.length > 1 && P.pos < P.order.length - 1) next(true); }, 1500);
});

$('#pPlay').onclick = togglePlay;
$('#pNext').onclick = () => next(false);
$('#pPrev').onclick = prev;
$('#pShuffle').onclick = toggleShuffle;
$('#pRepeat').onclick = cycleRepeat;
$('#pClose').onclick = closePlayer;
$('#pQueue').onclick = openNowPlaying;
$('#npOpen').onclick = openNowPlaying;
$('#pMute').onclick = () => { audio.muted = !audio.muted; updateButtons(); };
const seekBar = $('#pSeek');
seekBar.addEventListener('input', () => { seeking = true; paintRange(seekBar); $('#pPos').textContent = fmtTime(seekBar.value / 1000 * audio.duration * 1000); });
seekBar.addEventListener('change', () => { audio.currentTime = seekBar.value / 1000 * audio.duration; seeking = false; });
const vol = $('#pVol');
vol.addEventListener('input', () => { audio.volume = vol.value / 100; audio.muted = false; paintRange(vol); updateButtons(); L.prefs.volume = audio.volume; save(); });
function syncVolumeUi() { vol.value = Math.round(audio.volume * 100); paintRange(vol); }
