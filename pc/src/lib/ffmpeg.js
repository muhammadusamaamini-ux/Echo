// FFmpeg: the engine behind the video editor, the audio editor, thumbnails and converting.
const { spawn } = require('child_process');

let ffmpegPath = null;
function bin() {
  if (ffmpegPath) return ffmpegPath;
  try {
    ffmpegPath = require('ffmpeg-static');
  } catch (e) {
    ffmpegPath = 'ffmpeg';
  }
  // inside the installed app the program sits next to the archive, not inside it
  if (ffmpegPath) ffmpegPath = ffmpegPath.replace('app.asar', 'app.asar.unpacked');
  return ffmpegPath;
}

const running = new Map(); // job id -> child process

function parseTime(s) {
  const m = /(\d+):(\d{2}):(\d{2}(?:\.\d+)?)/.exec(s);
  return m ? (+m[1]) * 3600 + (+m[2]) * 60 + parseFloat(m[3]) : 0;
}

/** Runs FFmpeg. onProgress(0..100) while it works. Resolves with stderr text. */
function run(args, totalSec, onProgress, jobId) {
  return new Promise((resolve, reject) => {
    let err = '';
    const p = spawn(bin(), args, { windowsHide: true });
    if (jobId) running.set(jobId, p);
    p.stderr.on('data', (d) => {
      const s = d.toString();
      err += s;
      if (err.length > 400000) err = err.slice(-200000);
      const m = /time=\s*(\d+:\d{2}:\d{2}(?:\.\d+)?)/g;
      let last = null, x;
      while ((x = m.exec(s))) last = x[1];
      if (last && totalSec > 0 && onProgress) onProgress(Math.min(99, Math.round(parseTime(last) / totalSec * 100)));
    });
    p.stdout.on('data', () => {});
    p.on('error', (e) => { running.delete(jobId); reject(new Error('FFmpeg could not start: ' + e.message)); });
    p.on('close', (code) => {
      running.delete(jobId);
      if (code === 0) resolve(err);
      else {
        const e = new Error(err.trim().split(/\r?\n/).slice(-12).join('\n') || ('FFmpeg stopped (' + code + ')'));
        e.cancelled = code === null || code === 255;
        reject(e);
      }
    });
  });
}

function cancel(jobId) {
  const p = running.get(jobId);
  if (p) { try { p.kill('SIGKILL'); } catch (e) { /* already gone */ } }
}

function infoFrom(text) {
  const d = /Duration:\s*(\d+:\d{2}:\d{2}(?:\.\d+)?)/.exec(text);
  const v = /Stream #[^\n]*Video:[^\n]*?(\d{2,5})x(\d{2,5})/.exec(text);
  let rotation = 0;
  const r1 = /rotate\s*:\s*(-?\d+)/.exec(text);
  const r2 = /rotation of (-?[\d.]+) degrees/.exec(text);
  if (r1) rotation = +r1[1]; else if (r2) rotation = Math.round(+r2[1]);
  let width = v ? +v[1] : 0, height = v ? +v[2] : 0;
  if (Math.abs(rotation) % 180 === 90) { const t = width; width = height; height = t; }
  return {
    duration: d ? parseTime(d[1]) : 0, width, height,
    hasVideo: /Stream #[^\n]*Video:/.test(text) && !/Video:[^\n]*(mjpeg|png)[^\n]*attached pic/.test(text),
    hasAudio: /Stream #[^\n]*Audio:/.test(text), rotation,
  };
}

function probeText(file) {
  return new Promise((resolve) => {
    let err = '';
    const p = spawn(bin(), ['-hide_banner', '-i', file], { windowsHide: true });
    p.stderr.on('data', (d) => { err += d.toString(); });
    p.on('error', () => resolve(''));
    p.on('close', () => resolve(err));
  });
}

/** A small picture from a video (and its length). */
async function thumb(file, out, at) {
  const text = await probeText(file);
  const info = infoFrom(text);
  const t = at != null ? at : Math.min(5, info.duration * 0.2 || 0);
  try {
    await run(['-hide_banner', '-y', '-ss', t.toFixed(2), '-i', file, '-frames:v', '1', '-vf', 'scale=320:-2', '-q:v', '5', out], 0);
    info.thumb = out;
  } catch (e) {
    info.thumb = null;
  }
  return info;
}

async function frame(file, at, out) {
  await run(['-hide_banner', '-y', '-ss', at.toFixed(3), '-i', file, '-frames:v', '1', out], 0);
  return out;
}

function sec(n) { return Math.max(0, n).toFixed(3); }

/** atempo only takes 0.5–2, so bigger changes are chained. */
function tempo(speed) {
  const parts = [];
  let s = speed;
  while (s > 2) { parts.push('atempo=2'); s /= 2; }
  while (s < 0.5) { parts.push('atempo=0.5'); s /= 0.5; }
  if (Math.abs(s - 1) > 0.001) parts.push('atempo=' + s.toFixed(4));
  return parts;
}

function audioChain(speed, pitch, volume) {
  const f = ['asetpts=PTS-STARTPTS', 'aresample=48000'];
  f.push(...tempo(speed || 1));
  if (pitch && Math.abs(pitch - 1) > 0.001) {
    f.push('asetrate=48000*' + pitch.toFixed(4), 'aresample=48000', ...tempo(1 / pitch));
  }
  if (volume != null && Math.abs(volume - 1) > 0.001) f.push('volume=' + volume.toFixed(3));
  f.push('aresample=48000', 'aformat=sample_fmts=fltp:channel_layouts=stereo');
  return f.join(',');
}

const FILTERS = {
  'Black & White': 'hue=s=0',
  'Sepia': 'colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131',
  'Vintage': 'curves=preset=vintage',
  'Warm': 'colorbalance=rs=.08:gs=.02:bs=-.08:rm=.05:bm=-.05',
  'Cool': 'colorbalance=rs=-.08:bs=.1:rm=-.05:bm=.06',
  'Vivid': 'eq=saturation=1.4:contrast=1.1',
  'Dramatic': 'eq=contrast=1.35:saturation=0.85',
  'Noir': 'hue=s=0,eq=contrast=1.4',
  'Invert': 'negate',
};

/** Width and height of the finished video (even numbers). */
function outSize(p) {
  const first = p.clips[0] || {};
  let w = first.width || 1280, h = first.height || 720;
  if (p.rotate % 180 !== 0) { const t = w; w = h; h = t; }
  if (p.aspect > 0) {
    const cur = w / h;
    if (p.aspectFill) { if (cur > p.aspect) w = Math.round(h * p.aspect); else h = Math.round(w / p.aspect); }
    else if (cur > p.aspect) h = Math.round(w / p.aspect); else w = Math.round(h * p.aspect);
  }
  let scale = 1;
  const shortSide = Math.min(w, h);
  if (p.quality > 0 && shortSide > p.quality) scale = p.quality / shortSide;
  const longSide = Math.max(w, h);
  if (longSide * scale > 3840) scale = 3840 / longSide;
  return [Math.max(2, Math.round(w * scale / 2) * 2), Math.max(2, Math.round(h * scale / 2) * 2)];
}

function clipLength(c) {
  return c.image ? (c.photoSec || 3) : Math.max(0.1, (c.end - c.start) / (c.speed || 1));
}

/**
 * The FFmpeg command for the video editor.
 * audioOnly = just the sound (Extract audio) as MP3.
 */
function videoArgs(p, out, audioOnly) {
  const [W, H] = outSize(p);
  const args = ['-hide_banner', '-y'];
  const fc = [];
  let idx = 0;
  const clips = audioOnly ? p.clips.filter((c) => !c.image) : p.clips;
  const vIn = [], aIn = [];
  for (const c of clips) {
    if (c.image) {
      args.push('-loop', '1', '-framerate', '30', '-t', sec(c.photoSec || 3), '-i', c.path);
    } else {
      args.push('-ss', sec(c.start), '-to', sec(c.end), '-i', c.path);
    }
    vIn.push(idx++);
  }
  for (const c of clips) {
    if (c.image || !c.hasAudio) {
      args.push('-f', 'lavfi', '-t', sec(clipLength(c)), '-i', 'anullsrc=r=48000:cl=stereo');
      aIn.push(idx++);
    } else aIn.push(null);
  }
  let overlayIdx = null, musicIdx = null;
  if (!audioOnly && p.overlay) { args.push('-loop', '1', '-i', p.overlay); overlayIdx = idx++; }
  if (p.music) { args.push('-stream_loop', '-1', '-i', p.music); musicIdx = idx++; }

  const look = [];
  if (p.rotate === 90) look.push('transpose=1');
  else if (p.rotate === 180) look.push('hflip', 'vflip');
  else if (p.rotate === 270) look.push('transpose=2');
  if (p.flipH) look.push('hflip');
  if (p.flipV) look.push('vflip');
  if (FILTERS[p.filter]) look.push(FILTERS[p.filter]);
  if (p.brightness || p.contrast || p.saturation) {
    look.push(`eq=brightness=${((p.brightness || 0) / 200).toFixed(3)}:contrast=${(1 + (p.contrast || 0) / 100).toFixed(3)}:saturation=${(1 + (p.saturation || 0) / 100).toFixed(3)}`);
  }
  const fit = p.aspectFill
    ? `scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H}`
    : `scale=${W}:${H}:force_original_aspect_ratio=decrease,pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2:black`;

  const pairs = [];
  clips.forEach((c, i) => {
    if (!audioOnly) {
      const v = [];
      if (!c.image && (c.speed || 1) !== 1) v.push(`setpts=(PTS-STARTPTS)/${(c.speed).toFixed(4)}`);
      else v.push('setpts=PTS-STARTPTS');
      v.push(...look, fit, 'setsar=1', 'fps=30', 'format=yuv420p');
      fc.push(`[${vIn[i]}:v]${v.join(',')}[v${i}]`);
    }
    const vol = (c.volume == null ? 1 : c.volume) * (p.originalVolume == null ? 1 : p.originalVolume);
    if (aIn[i] == null) fc.push(`[${vIn[i]}:a]${audioChain(c.speed, c.pitch, vol)}[a${i}]`);
    else fc.push(`[${aIn[i]}:a]${audioChain(1, 1, 1)}[a${i}]`);
    pairs.push(audioOnly ? `[a${i}]` : `[v${i}][a${i}]`);
  });
  fc.push(`${pairs.join('')}concat=n=${clips.length}:v=${audioOnly ? 0 : 1}:a=1${audioOnly ? '' : '[vc]'}[ac]`);
  let vLabel = '[vc]', aLabel = '[ac]';
  if (overlayIdx != null) { fc.push(`[vc][${overlayIdx}:v]overlay=0:0:shortest=1[vo]`); vLabel = '[vo]'; }
  if (musicIdx != null) {
    fc.push(`[${musicIdx}:a]volume=${(p.musicVolume == null ? 0.8 : p.musicVolume).toFixed(3)},aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[mu]`);
    fc.push(`[ac][mu]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[ao]`);
    aLabel = '[ao]';
  }
  args.push('-filter_complex', fc.join(';'));
  if (!audioOnly) args.push('-map', vLabel);
  args.push('-map', aLabel);
  if (audioOnly) args.push('-vn', '-c:a', 'libmp3lame', '-q:a', '2');
  else args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart');
  args.push(out);
  const total = clips.reduce((t, c) => t + clipLength(c), 0);
  return { args, total, size: [W, H] };
}

/** The FFmpeg command for the audio editor. format: mp3 | m4a | wav */
function audioArgs(p, out, format) {
  const args = ['-hide_banner', '-y'];
  const fc = [];
  p.parts.forEach((x) => args.push('-ss', sec(x.start), '-to', sec(x.end), '-i', x.path));
  let musicIdx = null;
  if (p.mix && p.mixVolume > 0) { args.push('-stream_loop', '-1', '-i', p.mix); musicIdx = p.parts.length; }
  p.parts.forEach((x, i) => fc.push(`[${i}:a]${audioChain(x.speed, x.pitch, x.volume)}[a${i}]`));
  const total = p.parts.reduce((t, x) => t + Math.max(0.1, (x.end - x.start) / (x.speed || 1)), 0);
  const fades = [];
  if (p.fadeIn > 0) fades.push(`afade=t=in:st=0:d=${p.fadeIn}`);
  if (p.fadeOut > 0) fades.push(`afade=t=out:st=${Math.max(0, total - p.fadeOut).toFixed(3)}:d=${p.fadeOut}`);
  fc.push(`${p.parts.map((x, i) => `[a${i}]`).join('')}concat=n=${p.parts.length}:v=0:a=1${fades.length ? ',' + fades.join(',') : ''}[ac]`);
  let label = '[ac]';
  if (musicIdx != null) {
    fc.push(`[${musicIdx}:a]volume=${p.mixVolume.toFixed(3)},aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[mu]`);
    fc.push(`[ac][mu]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[ao]`);
    label = '[ao]';
  }
  args.push('-filter_complex', fc.join(';'), '-map', label, '-vn');
  if (format === 'wav') args.push('-c:a', 'pcm_s16le');
  else if (format === 'm4a') args.push('-c:a', 'aac', '-b:a', '192k');
  else args.push('-c:a', 'libmp3lame', '-q:a', '2');
  args.push(out);
  return { args, total };
}

/** Peaks for drawing a waveform (0..1 values). */
function waveform(file, buckets = 1500) {
  return new Promise((resolve) => {
    const p = spawn(bin(), ['-hide_banner', '-i', file, '-ac', '1', '-ar', '2000', '-f', 's16le', '-'], { windowsHide: true });
    const chunks = [];
    let size = 0;
    p.stdout.on('data', (d) => { if (size < 60e6) { chunks.push(d); size += d.length; } });
    p.stderr.on('data', () => {});
    p.on('error', () => resolve([]));
    p.on('close', () => {
      const buf = Buffer.concat(chunks);
      const n = Math.floor(buf.length / 2);
      if (!n) return resolve([]);
      const per = Math.max(1, Math.floor(n / buckets));
      const out = [];
      let max = 1;
      for (let b = 0; b * per < n; b++) {
        let peak = 0;
        for (let i = b * per; i < Math.min(n, (b + 1) * per); i++) peak = Math.max(peak, Math.abs(buf.readInt16LE(i * 2)));
        out.push(peak);
        max = Math.max(max, peak);
      }
      resolve(out.map((v) => v / max));
    });
  });
}

/** Re-encodes a video that Windows can't play into a normal MP4. */
function convertArgs(file, out) {
  return ['-hide_banner', '-y', '-i', file, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21',
    '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart', out];
}

module.exports = { run, cancel, probe: async (f) => infoFrom(await probeText(f)), thumb, frame, videoArgs, audioArgs,
  waveform, convertArgs, outSize, bin };
