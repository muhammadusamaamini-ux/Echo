// Plays videos Windows can't play by itself (MKV, AVI, HEVC/H.265, 10-bit, WMV, FLV…): FFmpeg turns them into a
// normal MP4 stream while they play, so nothing is copied or saved. Seeking restarts the stream at that moment.
// The player asks for  echostream://play?file=<path>&t=<seconds>
'use strict';
const { protocol } = require('electron');
const { spawn } = require('child_process');
const { Readable } = require('stream');
const ff = require('./ffmpeg');

const procs = new Set();
const infoCache = new Map();

/** Must run before the app is ready. */
function registerScheme() {
  protocol.registerSchemesAsPrivileged([{ scheme: 'echostream', privileges: { stream: true, supportFetchAPI: true, bypassCSP: true } }]);
}

function probeText(file) {
  return new Promise((resolve) => {
    let err = '';
    const p = spawn(ff.bin(), ['-hide_banner', '-i', file], { windowsHide: true });
    p.stderr.on('data', (d) => { err += d.toString(); });
    p.on('error', () => resolve(''));
    p.on('close', () => resolve(err));
  });
}

/** What's inside the file: can the picture be passed through as it is (8-bit H.264), or must it be converted? */
async function info(file) {
  if (infoCache.has(file)) return infoCache.get(file);
  const text = await probeText(file);
  const d = /Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)/.exec(text);
  const v = /Stream #[^\n]*Video:\s*([^\n]+)/.exec(text);
  const vline = v ? v[1] : '';
  const r = {
    duration: d ? (+d[1]) * 3600 + (+d[2]) * 60 + parseFloat(d[3]) : 0,
    hasVideo: !!v && !/attached pic/.test(vline),
    hasAudio: /Stream #[^\n]*Audio:/.test(text),
    copyVideo: /^h264\b/.test(vline) && /\byuv420p\b(?!10)/.test(vline) && !/yuv420p10/.test(vline),
  };
  infoCache.set(file, r);
  return r;
}

function stopAll() {
  for (const p of procs) { try { p.kill('SIGKILL'); } catch (e) { /* gone */ } }
  procs.clear();
}

function handle() {
  protocol.handle('echostream', async (req) => {
    const u = new URL(req.url);
    const file = u.searchParams.get('file');
    const t = Math.max(0, parseFloat(u.searchParams.get('t')) || 0);
    if (!file) return new Response('no file', { status: 400 });
    const inf = await info(file);
    stopAll(); // one video at a time; an older stream (before a seek) is finished with
    const args = ['-hide_banner', '-loglevel', 'error', '-nostdin'];
    if (t > 0) args.push('-ss', t.toFixed(2));
    args.push('-i', file, '-map', '0:v:0?', '-map', '0:a:0?', '-sn', '-dn');
    if (inf.copyVideo && t === 0) args.push('-c:v', 'copy'); // (a copy can only start at a keyframe, so seeks convert)
    else args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21', '-pix_fmt', 'yuv420p', '-g', '48',
      '-vf', 'scale=trunc(min(iw\\,1920)/2)*2:-2'); // up to 1080p wide, so it keeps up in real time
    args.push('-c:a', 'aac', '-b:a', '192k', '-ac', '2',
      '-f', 'mp4', '-movflags', 'frag_keyframe+empty_moov+default_base_moof', 'pipe:1');
    const p = spawn(ff.bin(), args, { windowsHide: true });
    procs.add(p);
    p.on('close', () => procs.delete(p));
    p.on('error', () => procs.delete(p));
    p.stderr.on('data', () => {});
    if (req.signal) req.signal.addEventListener('abort', () => { try { p.kill('SIGKILL'); } catch (e) { /* gone */ } });
    return new Response(Readable.toWeb(p.stdout), { status: 200, headers: { 'Content-Type': 'video/mp4', 'Cache-Control': 'no-store' } });
  });
}

module.exports = { registerScheme, handle, info, stopAll };
