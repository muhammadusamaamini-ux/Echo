// YouTube downloads on the PC, using yt-dlp (bundled as resources/bin/yt-dlp.exe) and the bundled FFmpeg.
// The phone does the same with the NewPipe library; yt-dlp is its Windows equivalent.
'use strict';
const { app } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { bin: ffmpeg } = require('./ffmpeg');

let ready = null;

/** yt-dlp is copied into Echo's data folder so it can keep itself up to date (YouTube changes often). */
function ytdlp() {
  if (ready) return ready;
  ready = (async () => {
    const bundled = app.isPackaged ? path.join(process.resourcesPath, 'bin', 'yt-dlp.exe') : path.join(__dirname, '..', '..', 'bin', 'yt-dlp.exe');
    const mine = path.join(app.getPath('userData'), 'yt-dlp.exe');
    try {
      const b = fs.statSync(bundled);
      const m = fs.existsSync(mine) ? fs.statSync(mine) : null;
      if (!m || m.mtimeMs < b.mtimeMs) fs.copyFileSync(bundled, mine);
    } catch (e) {
      if (!fs.existsSync(mine)) return bundled;
    }
    // update at most once a day, in the background
    const stamp = path.join(app.getPath('userData'), 'yt-dlp.updated');
    let last = 0;
    try { last = +fs.readFileSync(stamp, 'utf8') || 0; } catch (e) { /* first time */ }
    if (Date.now() - last > 24 * 3600 * 1000) {
      try { fs.writeFileSync(stamp, String(Date.now())); } catch (e) { /* ignore */ }
      spawn(mine, ['-U'], { windowsHide: true }).on('error', () => {});
    }
    return mine;
  })();
  return ready;
}

function run(args, onLine) {
  return new Promise(async (resolve) => {
    const exe = await ytdlp();
    let out = '', err = '';
    const p = spawn(exe, args, { windowsHide: true, env: { ...process.env, PYTHONUTF8: '1', PYTHONIOENCODING: 'utf-8' } });
    const lines = (buf, isErr) => {
      const s = buf.toString('utf8');
      if (isErr) err += s; else out += s;
      if (onLine) s.split(/\r?\n|\r/).forEach((l) => l.trim() && onLine(l.trim()));
    };
    p.stdout.on('data', (d) => lines(d, false));
    p.stderr.on('data', (d) => lines(d, true));
    p.on('error', (e) => resolve({ code: -1, out, err: String(e.message || e) }));
    p.on('close', (code) => resolve({ code, out, err, proc: p }));
    if (onLine) onLine.proc = p;
  });
}

function explain(err) {
  if (/Sign in to confirm you.?re not a bot/i.test(err)) return 'YouTube is asking Echo to prove it\'s not a robot right now. Wait a while and try again.';
  if (/age.?restrict|confirm your age/i.test(err)) return 'This video is age-restricted, so it can\'t be downloaded.';
  if (/Private video|Video unavailable|not available|removed/i.test(err)) return 'This video isn\'t available (it may be private, deleted or blocked in your country).';
  if (/getaddrinfo|Unable to download webpage|timed out|network/i.test(err)) return 'Couldn\'t reach YouTube. Check your internet.';
  const lines = String(err || '').trim().split(/\r?\n/).filter((l) => l.trim() && !/^\s*ECHO |^\[download\]/.test(l));
  const last = lines.filter((l) => /ERROR/.test(l)).pop() || lines.pop();
  return 'Couldn\'t read this video. If it keeps failing, YouTube may have changed something; Echo updates its downloader by itself, so try again later.' + (last ? '\n\n' + last.replace(/^ERROR:\s*/, '').slice(0, 200) : '');
}

/** Title, channel and the qualities on offer. */
async function info(url) {
  const r = await run(['-J', '--no-playlist', '--no-warnings', url]);
  if (r.code !== 0) return { error: explain(r.err) };
  let j;
  try { j = JSON.parse(r.out); } catch (e) { return { error: explain(r.err || 'bad answer') }; }
  const heights = [...new Set((j.formats || []).filter((f) => f.vcodec && f.vcodec !== 'none' && f.height).map((f) => f.height))]
    .filter((h) => [2160, 1440, 1080, 720, 480, 360, 240, 144].includes(h)).sort((a, b) => b - a);
  return { id: j.id, title: j.title || 'YouTube video', author: (j.uploader || j.channel || '').replace(/ - Topic$/, ''), duration: j.duration || 0, heights };
}

const running = new Map();

/** Downloads audio (M4A, into Music › Echo) or video (MP4, into Videos › Echo).
 * yt-dlp saves into a private work folder under the video's ID (plain letters), and Echo then moves it to
 * its real name itself, so titles in Urdu, Arabic and other scripts work on every Windows setup. */
async function download(id, url, choice, onProgress) {
  const audio = choice.audio;
  const dir = path.join(app.getPath(audio ? 'music' : 'videos'), 'Echo');
  fs.mkdirSync(dir, { recursive: true });
  const work = path.join(app.getPath('userData'), 'yt-work', id + '-' + Date.now());
  fs.mkdirSync(work, { recursive: true });
  const h = choice.height || 720;
  const args = ['--no-playlist', '--no-warnings', '--newline', '--progress', '--no-mtime', '--no-part',
    '--ffmpeg-location', ffmpeg(), '-P', work, '-o', 'echo_%(id)s.%(ext)s',
    '--progress-template', 'download:ECHO %(progress._percent_str)s|%(progress._total_bytes_str)s|%(progress._total_bytes_estimate_str)s'];
  if (audio) args.push('-f', 'bestaudio[ext=m4a]/bestaudio', '-x', '--audio-format', 'm4a');
  else args.push('-f', `bv*[height<=${h}][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=${h}]+ba/b[height<=${h}]/b`, '--merge-output-format', 'mp4');
  args.push(url);
  const onLine = (l) => {
    if (l.startsWith('ECHO ')) {
      const [pct, total, est] = l.slice(5).split('|');
      onProgress({ pct: parseFloat(pct) || 0, size: (total && !/NA/.test(total) ? total : est && !/NA/.test(est) ? '~' + est : '').trim() });
    } else if (/\[Merger\]|\[ExtractAudio\]|\[FixupM4a\]|\[VideoConvertor\]/.test(l)) onProgress({ pct: 100, step: 'Finishing…' });
  };
  const p = run(args, onLine);
  setTimeout(() => { if (onLine.proc) running.set(id, onLine.proc); }, 0);
  const r = await p;
  running.delete(id);
  const want = audio ? '.m4a' : '.mp4';
  let got = null;
  try {
    const files = fs.readdirSync(work).filter((f) => !/\.(part|ytdl|temp|tmp)$/i.test(f) && !/\.f\d+\./.test(f))
      .map((f) => ({ f, size: fs.statSync(path.join(work, f)).size, ok: f.toLowerCase().endsWith(want) }))
      .sort((a, b) => (b.ok - a.ok) || (b.size - a.size));
    if (files.length && files[0].size > 0) got = path.join(work, files[0].f);
  } catch (e) { /* nothing saved */ }
  if (!got || (r.code !== 0 && r.code !== null && !got.toLowerCase().endsWith(want))) {
    try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* ignore */ }
    return { ok: false, cancelled: r.code === null, error: explain(r.err || r.out) };
  }
  const base = safeName(choice.title || 'YouTube ' + id);
  const ext = path.extname(got) || want;
  let dest = path.join(dir, base + ext);
  for (let i = 2; fs.existsSync(dest); i++) dest = path.join(dir, `${base} (${i})${ext}`);
  try { fs.renameSync(got, dest); } catch (e) { fs.copyFileSync(got, dest); }
  try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  return { ok: true, path: dest };
}

function safeName(t) {
  const s = String(t).replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/[. ]+$/, '');
  return (s || 'YouTube').slice(0, 120);
}

function cancel(id) { const p = running.get(id); if (p) p.kill(); }

module.exports = { info, download, cancel };
