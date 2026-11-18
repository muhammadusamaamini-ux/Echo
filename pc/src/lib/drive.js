// Reads a Google Drive folder that is shared as "Anyone with the link" (no sign-in).
// Songs are played online from Drive; nothing is downloaded.

const AUDIO = new Set(['mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'oga', 'opus', 'wma', 'amr', 'mka', 'weba']);
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';

function folderId(text) {
  if (!text) return null;
  const m = /(?:\/folders\/|[?&]id=)([A-Za-z0-9_-]{10,})/.exec(text);
  return m ? m[1] : null;
}

function streamUrl(fileId) {
  return `https://drive.usercontent.google.com/download?id=${fileId}&export=download&confirm=t`;
}

function unescapeHtml(s) {
  return s
    .replace(/<[^>]*>/g, '')
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

async function get(url) {
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'en' } });
  if (!r.ok) return null;
  const html = await r.text();
  if (html.includes('accounts.google.com/ServiceLogin') && !html.includes('flip-entry')) return null;
  return html;
}

function ext(name) {
  const i = name.lastIndexOf('.');
  return i < 0 ? '' : name.slice(i + 1).toLowerCase();
}

/** "01 - Artist - Song.mp3" -> { title: "Song", artist: "Artist" } */
function song(fileId, fileName, folderName) {
  let base = fileName.includes('.') ? fileName.slice(0, fileName.lastIndexOf('.')) : fileName;
  base = base.replace(/^\d{1,3}[ ._-]+/, '').replace(/_/g, ' ').trim();
  let title = base, artist = '';
  const dash = base.indexOf(' - ');
  if (dash > 0) { artist = base.slice(0, dash).trim(); title = base.slice(dash + 3).trim(); }
  return {
    id: 'gd_' + fileId, source: 'drive', uri: streamUrl(fileId),
    title: title || fileName, artist, album: folderName || '', duration: 0, added: Date.now(),
  };
}

async function readNode(id, name, depth, seen, f) {
  if (depth > 8 || seen.has(id)) return null;
  seen.add(id);
  const html = await get('https://drive.google.com/embeddedfolderview?id=' + id);
  if (html == null) { if (depth > 0) f.complete = false; return null; }
  const n = { id, name, songs: [], children: [] };
  if (!n.name) {
    const t = /<title>([\s\S]*?)<\/title>/.exec(html);
    n.name = t ? unescapeHtml(t[1]).replace(' - Google Drive', '').trim() : '';
    if (!n.name) n.name = 'Google Drive';
  }
  const subs = [];
  const parts = html.split('class="flip-entry"');
  for (let i = 1; i < parts.length; i++) {
    const p = parts[i];
    const idm = /id="entry-([A-Za-z0-9_-]+)"/.exec(p);
    const tm = /class="flip-entry-title">([\s\S]*?)<\/div>/.exec(p);
    if (!idm || !tm) continue;
    const href = /href="([^"]+)"/.exec(p);
    const entryName = unescapeHtml(tm[1]).trim();
    if (href && href[1].includes('/folders/')) { subs.push([idm[1], entryName]); continue; }
    if (!AUDIO.has(ext(entryName))) continue;
    n.songs.push(song(idm[1], entryName, n.name));
    f.songCount++;
  }
  for (const [sid, sname] of subs) {
    try {
      const c = await readNode(sid, sname, depth + 1, seen, f);
      if (c && (c.songs.length || c.children.length)) n.children.push(c);
    } catch (e) { f.complete = false; }
  }
  return n;
}

/** Reads a shared folder with all its sub-folders: { root, songCount, complete, error } */
async function read(id) {
  const f = { root: null, songCount: 0, complete: true, error: null };
  try {
    f.root = await readNode(id, null, 0, new Set(), f);
    if (!f.root) f.error = 'This folder isn\'t shared. In Google Drive, share it as "Anyone with the link" and try again.';
    else if (f.songCount === 0) f.error = 'No songs were found in this folder or its sub-folders. Make sure it has audio files (MP3, M4A…) and is shared as "Anyone with the link".';
  } catch (e) {
    f.error = 'Couldn\'t open the folder. Check your internet, and that the folder is shared as "Anyone with the link".';
  }
  return f;
}

module.exports = { read, folderId, streamUrl, AUDIO, song, unescapeHtml };
