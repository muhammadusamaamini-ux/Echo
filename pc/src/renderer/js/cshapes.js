// Shaped collages: circles, flowers, diamonds, stars, hearts, hexagons, bubbles, stripes, zigzags, waves,
// sunbursts, pies, arches and scrapbook prints. Every box is a polygon (curves are drawn with many points),
// so the phone uses exactly the same shapes (Photos.java).
// shapeLayouts(n, r) → [{ name, cells: [{ pts: [[x, y], …] in 0–1, card?: true }] }]  (r = width / height)
'use strict';

function shapeLayouts(n, r) {
  if (n < 2) return [];
  const W = r >= 1 ? r : 1, H = r >= 1 ? 1 : 1 / r; // a box with true proportions, so circles stay round
  const m = Math.min(W, H), cx = W / 2, cy = H / 2;
  const norm = (pts) => pts.map(([x, y]) => [Math.max(0, Math.min(1, x / W)), Math.max(0, Math.min(1, y / H))]);
  const rect = [[0, 0], [W, 0], [W, H], [0, H]];
  const circle = (x, y, rad, k = 72) => Array.from({ length: k }, (_, i) => [x + rad * Math.cos(2 * Math.PI * i / k), y + rad * Math.sin(2 * Math.PI * i / k)]);
  const ellipse = (x, y, a, b, ang, k = 60) => Array.from({ length: k }, (_, i) => { const t = 2 * Math.PI * i / k; const ex = a * Math.cos(t), ey = b * Math.sin(t); return [x + ex * Math.cos(ang) - ey * Math.sin(ang), y + ex * Math.sin(ang) + ey * Math.cos(ang)]; });
  const poly = (x, y, rad, sides, rot = -Math.PI / 2) => Array.from({ length: sides }, (_, i) => [x + rad * Math.cos(rot + 2 * Math.PI * i / sides), y + rad * Math.sin(rot + 2 * Math.PI * i / sides)]);
  const star = (x, y, ro, ri, points = 5) => Array.from({ length: points * 2 }, (_, i) => { const a = -Math.PI / 2 + Math.PI * i / points; const rr = i % 2 ? ri : ro; return [x + rr * Math.cos(a), y + rr * Math.sin(a)]; });
  const heart = (x, y, s, k = 90) => Array.from({ length: k }, (_, i) => { const t = 2 * Math.PI * i / k; const hx = 16 * Math.pow(Math.sin(t), 3); const hy = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t); return [x + hx * s / 17, y - hy * s / 17 - s * 0.08]; });
  // cut a polygon by a convex window (Sutherland–Hodgman)
  const clip = (subject, win) => {
    let out = subject;
    for (let i = 0; i < win.length && out.length; i++) {
      const a = win[i], b = win[(i + 1) % win.length];
      const inside = (p) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0;
      const cross = (p, q) => { const A1 = q[1] - p[1], B1 = p[0] - q[0], C1 = A1 * p[0] + B1 * p[1]; const A2 = b[1] - a[1], B2 = a[0] - b[0], C2 = A2 * a[0] + B2 * a[1]; const d = A1 * B2 - A2 * B1; return d === 0 ? p : [(B2 * C1 - B1 * C2) / d, (A1 * C2 - A2 * C1) / d]; };
      const inp = out;
      out = [];
      for (let j = 0; j < inp.length; j++) {
        const p = inp[j], q = inp[(j + 1) % inp.length];
        if (inside(q)) { if (!inside(p)) out.push(cross(p, q)); out.push(q); } else if (inside(p)) out.push(cross(p, q));
      }
    }
    return out;
  };
  const area = (p) => Math.abs(p.reduce((s, [x, y], i) => { const [x2, y2] = p[(i + 1) % p.length]; return s + x * y2 - x2 * y; }, 0) / 2);
  // wedges from the centre to the edge of the collage
  const wedges = (k, rot = -Math.PI / 2) => Array.from({ length: k }, (_, i) => {
    const a1 = rot + 2 * Math.PI * i / k, a2 = rot + 2 * Math.PI * (i + 1) / k, R = W + H;
    const pts = [[cx, cy]];
    for (let s = 0; s <= 8; s++) { const a = a1 + (a2 - a1) * s / 8; pts.push([cx + R * Math.cos(a), cy + R * Math.sin(a)]); }
    return clip(pts.reverse(), rect);
  });
  // a shape cut into equal strips (vertical) or into rows of tiles
  const strips = (shape, k, vertical = true) => {
    const xs = shape.map((p) => p[0]), ys = shape.map((p) => p[1]);
    const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
    return Array.from({ length: k }, (_, i) => {
      const win = vertical
        ? [[x0 + (x1 - x0) * i / k, y0 - 1], [x0 + (x1 - x0) * (i + 1) / k, y0 - 1], [x0 + (x1 - x0) * (i + 1) / k, y1 + 1], [x0 + (x1 - x0) * i / k, y1 + 1]]
        : [[x0 - 1, y0 + (y1 - y0) * i / k], [x1 + 1, y0 + (y1 - y0) * i / k], [x1 + 1, y0 + (y1 - y0) * (i + 1) / k], [x0 - 1, y0 + (y1 - y0) * (i + 1) / k]];
      return clip(shape, win);
    });
  };
  const tiles = (shape, k) => { // rows of tiles, more in the middle rows
    const rows = k <= 3 ? 1 : k <= 6 ? 2 : 3;
    const base = Math.floor(k / rows), extra = k % rows;
    const counts = Array.from({ length: rows }, (_, i) => base + (i >= (rows - extra) / 2 && i < (rows - extra) / 2 + extra ? 1 : 0));
    let sum = counts.reduce((a, b) => a + b, 0);
    for (let i = 0; sum < k; i++, sum++) counts[i % rows]++;
    const ys = shape.map((p) => p[1]), y0 = Math.min(...ys), y1 = Math.max(...ys);
    const out = [];
    counts.forEach((c, row) => {
      const band = [[-1, y0 + (y1 - y0) * row / rows], [W + 1, y0 + (y1 - y0) * row / rows], [W + 1, y0 + (y1 - y0) * (row + 1) / rows], [-1, y0 + (y1 - y0) * (row + 1) / rows]];
      const part = clip(shape, band);
      out.push(...strips(part, c, true));
    });
    return out;
  };
  const L = [];
  const add = (name, cells) => {
    cells = cells.filter((c) => c.pts.length >= 3 && area(c.pts) > 0.0005 * W * H);
    if (cells.length === n) L.push({ name, cells: cells.map((c) => ({ ...c, pts: norm(c.pts) })) });
  };
  const P = (pts) => ({ pts });

  // 1. circle in the middle, the rest as slices around it
  add('Circle in the middle', [...wedges(n - 1).map(P), P(circle(cx, cy, m * 0.3))]);
  // 2. flower: round centre and petals
  {
    const k = n - 1, d = m * 0.29, a = m * 0.19, b = Math.min(m * 0.15, Math.PI * d / k * 0.62);
    add('Flower', [...Array.from({ length: k }, (_, i) => { const t = -Math.PI / 2 + 2 * Math.PI * i / k; return P(ellipse(cx + d * Math.cos(t), cy + d * Math.sin(t), a, b, t)); }), P(circle(cx, cy, m * 0.15))]);
  }
  // 3. diamond / star / hexagon in the middle
  add('Diamond in the middle', [...wedges(n - 1, -Math.PI / 4).map(P), P(poly(cx, cy, m * 0.36, 4))]);
  add('Star in the middle', [...wedges(n - 1).map(P), P(star(cx, cy, m * 0.4, m * 0.17))]);
  add('Hexagon in the middle', [...wedges(n - 1, -Math.PI / 6).map(P), P(poly(cx, cy, m * 0.32, 6))]);
  // 4. a shape filled with your photos
  const hrt = heart(cx, cy, m * 0.48), cir = circle(cx, cy, m * 0.47), hex = poly(cx, cy, m * 0.48, 6, 0), dia = poly(cx, cy, m * 0.49, 4), str = star(cx, cy, m * 0.5, m * 0.23);
  add('Heart (strips)', strips(hrt, n).map(P));
  add('Heart (tiles)', tiles(hrt, n).map(P));
  add('Circle (strips)', strips(cir, n).map(P));
  add('Circle (tiles)', tiles(cir, n).map(P));
  add('Pie', Array.from({ length: n }, (_, i) => { const a1 = -Math.PI / 2 + 2 * Math.PI * i / n, a2 = -Math.PI / 2 + 2 * Math.PI * (i + 1) / n; const pts = [[cx, cy]]; for (let s = 0; s <= 24; s++) { const a = a1 + (a2 - a1) * s / 24; pts.push([cx + m * 0.47 * Math.cos(a), cy + m * 0.47 * Math.sin(a)]); } return P(pts); }));
  add('Hexagon (tiles)', tiles(hex, n).map(P));
  add('Diamond (strips)', strips(dia, n).map(P));
  add('Star (strips)', strips(str, n).map(P));
  // 5. sunburst: rays from the centre
  add('Sunburst', wedges(n).map(P));
  // 6. bubbles and honeycomb
  {
    const cols = Math.ceil(Math.sqrt(n * W / H)), rows = Math.ceil(n / cols);
    const cw = W / cols, ch = H / rows, rad = Math.min(cw, ch) * 0.47;
    const cells = [], hexes = [];
    for (let i = 0; i < n; i++) {
      const row = Math.floor(i / cols), inRow = row === rows - 1 ? n - row * cols : cols;
      const col = i % cols, off = (W - inRow * cw) / 2;
      const x = off + cw * (col + 0.5), y = ch * (row + 0.5);
      cells.push(P(circle(x, y, rad * (i % 3 === 0 ? 1 : 0.86))));
      hexes.push(P(poly(x, y, rad * 1.05, 6, 0)));
    }
    add('Bubbles', cells);
    add('Honeycomb', hexes);
  }
  // 7. slanted stripes, zigzag and waves
  {
    const s = W * 0.12;
    add('Slanted stripes', Array.from({ length: n }, (_, i) => { const x1 = W * i / n, x2 = W * (i + 1) / n; return P(clip([[x1 + (i ? s : 0), 0], [x2 + (i < n - 1 ? s : 0), 0], [x2 - (i < n - 1 ? s : 0), H], [x1 - (i ? s : 0), H]].reverse(), rect)); }));
    const edge = (y, kind) => { const pts = []; const teeth = 6; for (let j = 0; j <= 48; j++) { const x = W * j / 48; const t = (j / 48) * teeth; const amp = H * 0.045; const dy = kind === 'zig' ? amp * (2 * Math.abs(t - Math.floor(t) - 0.5) * 2 - 1) : amp * Math.sin(t * 2 * Math.PI); pts.push([x, y + dy]); } return pts; };
    for (const kind of ['zig', 'wave']) {
      const cells = [];
      for (let i = 0; i < n; i++) {
        const top = i === 0 ? [[0, 0], [W, 0]] : edge(H * i / n, kind);
        const bot = i === n - 1 ? [[W, H], [0, H]] : edge(H * (i + 1) / n, kind).reverse();
        cells.push(P([...top, ...bot].map(([x, y]) => [x, Math.max(0, Math.min(H, y))])));
      }
      add(kind === 'zig' ? 'Zigzag' : 'Waves', cells);
    }
  }
  // 8. arches (windows side by side)
  {
    const g = W * 0.02, w = (W - g * (n + 1)) / n, rad = w / 2;
    if (rad * 2 < H * 0.9) add('Arches', Array.from({ length: n }, (_, i) => { const x = g + i * (w + g); const top = H * 0.12 + rad; const pts = [[x, H * 0.92]]; for (let s = 0; s <= 24; s++) { const a = Math.PI + Math.PI * s / 24; pts.push([x + rad + rad * Math.cos(a), top + rad * Math.sin(a)]); } pts.push([x + w, H * 0.92]); return P(pts); }));
  }
  // 9. scrapbook: tilted photo prints with white frames
  {
    const cols = Math.ceil(Math.sqrt(n * W / H)), rows = Math.ceil(n / cols);
    const cw = W / cols, ch = H / rows;
    const cells = [];
    for (let i = 0; i < n; i++) {
      const row = Math.floor(i / cols), col = i % cols;
      const inRow = row === rows - 1 ? n - row * cols : cols, off = (W - inRow * cw) / 2;
      const x = off + cw * (col + 0.5) + (((i * 37) % 7) - 3) * cw * 0.02, y = ch * (row + 0.5) + (((i * 53) % 7) - 3) * ch * 0.02;
      const ang = ((((i * 71) % 11) - 5) / 5) * 0.16;
      const hw = cw * 0.44, hh = ch * 0.44;
      const pts = [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]].map(([a, b]) => [x + a * Math.cos(ang) - b * Math.sin(ang), y + a * Math.sin(ang) + b * Math.cos(ang)]);
      cells.push({ pts, card: true });
    }
    add('Scrapbook', cells);
  }
  return L;
}
