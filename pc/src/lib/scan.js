// Finds music and videos in folders on the PC.
const fs = require('fs');
const path = require('path');

const AUDIO = new Set(['.mp3', '.m4a', '.aac', '.wav', '.flac', '.ogg', '.oga', '.opus', '.wma', '.amr', '.mka', '.weba']);
const VIDEO = new Set(['.mp4', '.mkv', '.webm', '.mov', '.avi', '.m4v', '.3gp', '.wmv', '.flv', '.ts', '.mpg', '.mpeg']);
const SKIP = new Set(['node_modules', '$recycle.bin', 'system volume information', 'appdata', 'windows', 'program files',
  'program files (x86)', '.git']);

function isAudio(p) { return AUDIO.has(path.extname(p).toLowerCase()); }
function isVideo(p) { return VIDEO.has(path.extname(p).toLowerCase()); }

async function list(dir) {
  try { return await fs.promises.readdir(dir, { withFileTypes: true }); } catch (e) { return []; }
}

/** A music folder as a tree: { name, path, files: [paths], children: [...] } (empty branches dropped). */
async function musicTree(dir, depth = 0, budget = { n: 0 }) {
  const node = { name: path.basename(dir) || dir, path: dir, files: [], children: [] };
  if (depth > 10 || budget.n > 50000) return node;
  const entries = await list(dir);
  entries.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
  for (const e of entries) {
    if (e.name.startsWith('.')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (SKIP.has(e.name.toLowerCase())) continue;
      const c = await musicTree(full, depth + 1, budget);
      if (c.files.length || c.children.length) node.children.push(c);
    } else if (e.isFile() && isAudio(e.name)) {
      node.files.push(full);
      budget.n++;
    }
  }
  return node;
}

/** All videos under the given folders: [{ path, name, folder, size, mtime }] */
async function videos(dirs) {
  const out = [];
  const seen = new Set();
  async function walk(dir, depth) {
    if (depth > 8 || out.length > 20000) return;
    const entries = await list(dir);
    for (const e of entries) {
      if (e.name.startsWith('.')) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (!SKIP.has(e.name.toLowerCase())) await walk(full, depth + 1);
      } else if (e.isFile() && isVideo(e.name) && !seen.has(full.toLowerCase())) {
        seen.add(full.toLowerCase());
        let st = null;
        try { st = await fs.promises.stat(full); } catch (err) { continue; }
        if (st.size < 50 * 1024) continue; // skip tiny junk files
        out.push({ path: full, name: path.basename(full, path.extname(full)), folder: path.basename(dir),
          folderPath: dir, size: st.size, mtime: st.mtimeMs });
      }
    }
  }
  for (const d of dirs) { if (d) await walk(d, 0); }
  out.sort((a, b) => b.mtime - a.mtime);
  return out;
}

module.exports = { musicTree, videos, isAudio, isVideo };
