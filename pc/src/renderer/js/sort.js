// Sorting. Every list remembers its own choice: one folder can be A–Z, another newest first, another by artist.
'use strict';

const SONG_SORTS = [
  ['custom', 'Custom order (as added)'], ['title', 'Name (A–Z)'], ['titleDesc', 'Name (Z–A)'], ['artist', 'Artist (A–Z)'],
  ['album', 'Album (A–Z)'], ['newest', 'Newest added first'], ['oldest', 'Oldest added first'],
  ['modified', 'Date modified (newest first)'], ['longest', 'Longest first'], ['shortest', 'Shortest first'],
];
const VIDEO_SORTS = [
  ['name', 'Name (A–Z)'], ['nameDesc', 'Name (Z–A)'], ['modified', 'Date modified (newest first)'],
  ['modifiedOld', 'Date modified (oldest first)'], ['biggest', 'Size (largest first)'], ['smallest', 'Size (smallest first)'],
];
const YT_SORTS = [['custom', 'Playlist order'], ['title', 'Name (A–Z)'], ['titleDesc', 'Name (Z–A)'], ['artist', 'Channel (A–Z)']];

const cmp = (a, b) => String(a || '').localeCompare(String(b || ''), undefined, { sensitivity: 'base', numeric: true });

function getSort(key, def) { return (L.prefs.sorts && L.prefs.sorts[key]) || def; }
function setSort(key, mode) {
  L.prefs.sorts = L.prefs.sorts || {};
  L.prefs.sorts[key] = mode;
  save();
  refreshView();
}

/** A "Sort: …" button with a menu; the choice is kept for this list only. */
function sortButton(key, options, def) {
  const cur = getSort(key, def);
  const label = (options.find((o) => o[0] === cur) || options[0])[1];
  return el('button.btn.sortbtn', { title: 'Sort this list', onclick: (e) => menu(e, options.map(([k, t]) => [(k === cur ? '✓  ' : '      ') + t, () => setSort(key, k)])) },
    icon('sort'), ' ' + label.replace(/ \(.*\)$/, ''));
}

function sortSongs(list, mode) {
  const a = list.slice();
  const by = {
    title: (x, y) => cmp(x.title, y.title),
    titleDesc: (x, y) => cmp(y.title, x.title),
    artist: (x, y) => cmp(x.artist || '￿', y.artist || '￿') || cmp(x.title, y.title), // songs with no artist go last
    album: (x, y) => cmp(x.album || '￿', y.album || '￿') || cmp(x.title, y.title),
    newest: (x, y) => (y.added || 0) - (x.added || 0),
    oldest: (x, y) => (x.added || 0) - (y.added || 0),
    modified: (x, y) => (y.mtime || y.added || 0) - (x.mtime || x.added || 0),
    longest: (x, y) => (y.duration || 0) - (x.duration || 0),
    shortest: (x, y) => (x.duration || 0) - (y.duration || 0),
  }[mode];
  if (by) a.sort(by);
  if (mode === 'modified') fillMtimes(list);
  return a;
}

/** Files' "date modified" is read from the PC the first time you sort by it. */
let mtimeBusy = false;
async function fillMtimes(list) {
  const need = list.filter((s) => s.path && s.mtime == null);
  if (!need.length || mtimeBusy) return;
  mtimeBusy = true;
  const times = await api.fileMtimes(need.map((s) => s.path));
  need.forEach((s, i) => { s.mtime = times[i] || 0; });
  mtimeBusy = false;
  save();
  refreshView();
}

function sortVideos(list, mode) {
  const a = list.slice();
  const by = {
    name: (x, y) => cmp(x.name, y.name),
    nameDesc: (x, y) => cmp(y.name, x.name),
    modified: (x, y) => y.mtime - x.mtime,
    modifiedOld: (x, y) => x.mtime - y.mtime,
    biggest: (x, y) => y.size - x.size,
    smallest: (x, y) => x.size - y.size,
  }[mode];
  if (by) a.sort(by);
  return a;
}

/** YouTube playlist items, with their place in the original playlist. */
function sortYt(items, mode) {
  const a = items.map((it, i) => ({ it, i }));
  const by = { title: (x, y) => cmp(x.it.t, y.it.t), titleDesc: (x, y) => cmp(y.it.t, x.it.t), artist: (x, y) => cmp(x.it.a, y.it.a) || cmp(x.it.t, y.it.t) }[mode];
  if (by) a.sort(by);
  return a;
}
