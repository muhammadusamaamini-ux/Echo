package com.usama.echo;

import java.util.ArrayList;
import java.util.List;

/**
 * Shaped collages (the same as on the PC, pc/src/renderer/js/cshapes.js): circles, flower, diamonds, stars, hearts,
 * hexagons, bubbles, stripes, zigzags, waves, sunburst, pie, arches and scrapbook prints. Every photo's place is a
 * polygon in 0–1 of the collage (curves use many points).
 */
final class CShapes {
    private CShapes() {
    }

    static final class Cell {
        float[][] pts;
        boolean card;
    }

    static final class Layout {
        String name;
        final List<Cell> cells = new ArrayList<>();
    }

    private static double W, H, M, CX, CY;

    /** r = width / height of the collage. */
    static List<Layout> layouts(int n, double r) {
        List<Layout> L = new ArrayList<>();
        if (n < 2) return L;
        W = r >= 1 ? r : 1;
        H = r >= 1 ? 1 : 1 / r;
        M = Math.min(W, H);
        CX = W / 2;
        CY = H / 2;
        double[][] rect = {{0, 0}, {W, 0}, {W, H}, {0, H}};

        // 1. a shape in the middle, the rest as slices around it
        add(L, n, "Circle in the middle", join(wedges(n - 1, -Math.PI / 2, rect), list(circle(CX, CY, M * 0.3, 72))));
        {
            int k = n - 1;
            double d = M * 0.29, a = M * 0.19, b = Math.min(M * 0.15, Math.PI * d / k * 0.62);
            List<double[][]> petals = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                double t = -Math.PI / 2 + 2 * Math.PI * i / k;
                petals.add(ellipse(CX + d * Math.cos(t), CY + d * Math.sin(t), a, b, t));
            }
            petals.add(circle(CX, CY, M * 0.15, 72));
            add(L, n, "Flower", petals);
        }
        add(L, n, "Diamond in the middle", join(wedges(n - 1, -Math.PI / 4, rect), list(poly(CX, CY, M * 0.36, 4, -Math.PI / 2))));
        add(L, n, "Star in the middle", join(wedges(n - 1, -Math.PI / 2, rect), list(star(CX, CY, M * 0.4, M * 0.17))));
        add(L, n, "Hexagon in the middle", join(wedges(n - 1, -Math.PI / 6, rect), list(poly(CX, CY, M * 0.32, 6, -Math.PI / 2))));
        // 2. a shape filled with your photos
        double[][] hrt = heart(CX, CY, M * 0.48), cir = circle(CX, CY, M * 0.47, 72), hex = poly(CX, CY, M * 0.48, 6, 0),
                dia = poly(CX, CY, M * 0.49, 4, -Math.PI / 2), str = star(CX, CY, M * 0.5, M * 0.23);
        add(L, n, "Heart (strips)", strips(hrt, n, true));
        add(L, n, "Heart (tiles)", tiles(hrt, n));
        add(L, n, "Circle (strips)", strips(cir, n, true));
        add(L, n, "Circle (tiles)", tiles(cir, n));
        {
            List<double[][]> pie = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double a1 = -Math.PI / 2 + 2 * Math.PI * i / n, a2 = -Math.PI / 2 + 2 * Math.PI * (i + 1) / n;
                double[][] p = new double[26][];
                p[0] = new double[]{CX, CY};
                for (int s = 0; s <= 24; s++) {
                    double a = a1 + (a2 - a1) * s / 24;
                    p[s + 1] = new double[]{CX + M * 0.47 * Math.cos(a), CY + M * 0.47 * Math.sin(a)};
                }
                pie.add(p);
            }
            add(L, n, "Pie", pie);
        }
        add(L, n, "Hexagon (tiles)", tiles(hex, n));
        add(L, n, "Diamond (strips)", strips(dia, n, true));
        add(L, n, "Star (strips)", strips(str, n, true));
        // 3. rays from the centre
        add(L, n, "Sunburst", wedges(n, -Math.PI / 2, rect));
        // 4. bubbles and honeycomb
        {
            int cols = (int) Math.ceil(Math.sqrt(n * W / H)), rows = (int) Math.ceil((double) n / cols);
            double cw = W / cols, ch = H / rows, rad = Math.min(cw, ch) * 0.47;
            List<double[][]> bubbles = new ArrayList<>(), hexes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int row = i / cols, inRow = row == rows - 1 ? n - row * cols : cols, col = i % cols;
                double off = (W - inRow * cw) / 2, x = off + cw * (col + 0.5), y = ch * (row + 0.5);
                bubbles.add(circle(x, y, rad * (i % 3 == 0 ? 1 : 0.86), 72));
                hexes.add(poly(x, y, rad * 1.05, 6, 0));
            }
            add(L, n, "Bubbles", bubbles);
            add(L, n, "Honeycomb", hexes);
        }
        // 5. slanted stripes, zigzag, waves
        {
            double s = W * 0.12;
            List<double[][]> st = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double x1 = W * i / n, x2 = W * (i + 1) / n;
                double[][] p = {{x1 + (i > 0 ? s : 0), 0}, {x2 + (i < n - 1 ? s : 0), 0}, {x2 - (i < n - 1 ? s : 0), H}, {x1 - (i > 0 ? s : 0), H}};
                st.add(clip(reverse(p), rect));
            }
            add(L, n, "Slanted stripes", st);
            for (int kind = 0; kind < 2; kind++) {
                List<double[][]> bands = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    List<double[]> pts = new ArrayList<>();
                    if (i == 0) { pts.add(new double[]{0, 0}); pts.add(new double[]{W, 0}); } else pts.addAll(edge(H * i / n, kind == 0));
                    if (i == n - 1) { pts.add(new double[]{W, H}); pts.add(new double[]{0, H}); } else {
                        List<double[]> e = edge(H * (i + 1) / n, kind == 0);
                        for (int j = e.size() - 1; j >= 0; j--) pts.add(e.get(j));
                    }
                    double[][] arr = new double[pts.size()][];
                    for (int j = 0; j < arr.length; j++) arr[j] = new double[]{pts.get(j)[0], Math.max(0, Math.min(H, pts.get(j)[1]))};
                    bands.add(arr);
                }
                add(L, n, kind == 0 ? "Zigzag" : "Waves", bands);
            }
        }
        // 6. arches
        {
            double g = W * 0.02, w = (W - g * (n + 1)) / n, rad = w / 2;
            if (rad * 2 < H * 0.9) {
                List<double[][]> arches = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double x = g + i * (w + g), top = H * 0.12 + rad;
                    double[][] p = new double[27][];
                    p[0] = new double[]{x, H * 0.92};
                    for (int s = 0; s <= 24; s++) {
                        double a = Math.PI + Math.PI * s / 24;
                        p[s + 1] = new double[]{x + rad + rad * Math.cos(a), top + rad * Math.sin(a)};
                    }
                    p[26] = new double[]{x + w, H * 0.92};
                    arches.add(p);
                }
                add(L, n, "Arches", arches);
            }
        }
        // 7. scrapbook prints (cards)
        {
            int cols = (int) Math.ceil(Math.sqrt(n * W / H)), rows = (int) Math.ceil((double) n / cols);
            double cw = W / cols, ch = H / rows;
            List<double[][]> cards = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int row = i / cols, col = i % cols, inRow = row == rows - 1 ? n - row * cols : cols;
                double off = (W - inRow * cw) / 2;
                double x = off + cw * (col + 0.5) + (((i * 37) % 7) - 3) * cw * 0.02, y = ch * (row + 0.5) + (((i * 53) % 7) - 3) * ch * 0.02;
                double ang = ((((i * 71) % 11) - 5) / 5.0) * 0.16, hw = cw * 0.44, hh = ch * 0.44;
                double[][] base = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
                double[][] p = new double[4][];
                for (int j = 0; j < 4; j++) p[j] = new double[]{x + base[j][0] * Math.cos(ang) - base[j][1] * Math.sin(ang), y + base[j][0] * Math.sin(ang) + base[j][1] * Math.cos(ang)};
                cards.add(p);
            }
            Layout before = L.isEmpty() ? null : L.get(L.size() - 1);
            add(L, n, "Scrapbook", cards);
            if (!L.isEmpty() && L.get(L.size() - 1) != before) for (Cell c : L.get(L.size() - 1).cells) c.card = true;
        }
        return L;
    }

    // ---------- helpers ----------
    private static void add(List<Layout> L, int n, String name, List<double[][]> cells) {
        Layout l = new Layout();
        l.name = name;
        for (double[][] p : cells) {
            if (p == null || p.length < 3 || area(p) <= 0.0005 * W * H) continue;
            Cell c = new Cell();
            c.pts = new float[p.length][];
            for (int i = 0; i < p.length; i++) c.pts[i] = new float[]{(float) Math.max(0, Math.min(1, p[i][0] / W)), (float) Math.max(0, Math.min(1, p[i][1] / H))};
            l.cells.add(c);
        }
        if (l.cells.size() == n) L.add(l);
    }

    private static List<double[][]> list(double[][] p) { List<double[][]> l = new ArrayList<>(); l.add(p); return l; }

    private static List<double[][]> join(List<double[][]> a, List<double[][]> b) { List<double[][]> l = new ArrayList<>(a); l.addAll(b); return l; }

    private static double[][] reverse(double[][] p) { double[][] o = new double[p.length][]; for (int i = 0; i < p.length; i++) o[i] = p[p.length - 1 - i]; return o; }

    static double area(double[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) { double[] a = p[i], b = p[(i + 1) % p.length]; s += a[0] * b[1] - b[0] * a[1]; }
        return Math.abs(s / 2);
    }

    private static double[][] circle(double x, double y, double r, int k) {
        double[][] o = new double[k][];
        for (int i = 0; i < k; i++) o[i] = new double[]{x + r * Math.cos(2 * Math.PI * i / k), y + r * Math.sin(2 * Math.PI * i / k)};
        return o;
    }

    private static double[][] ellipse(double x, double y, double a, double b, double ang) {
        int k = 60;
        double[][] o = new double[k][];
        for (int i = 0; i < k; i++) {
            double t = 2 * Math.PI * i / k, ex = a * Math.cos(t), ey = b * Math.sin(t);
            o[i] = new double[]{x + ex * Math.cos(ang) - ey * Math.sin(ang), y + ex * Math.sin(ang) + ey * Math.cos(ang)};
        }
        return o;
    }

    private static double[][] poly(double x, double y, double r, int sides, double rot) {
        double[][] o = new double[sides][];
        for (int i = 0; i < sides; i++) o[i] = new double[]{x + r * Math.cos(rot + 2 * Math.PI * i / sides), y + r * Math.sin(rot + 2 * Math.PI * i / sides)};
        return o;
    }

    private static double[][] star(double x, double y, double ro, double ri) {
        double[][] o = new double[10][];
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + Math.PI * i / 5, rr = i % 2 == 1 ? ri : ro;
            o[i] = new double[]{x + rr * Math.cos(a), y + rr * Math.sin(a)};
        }
        return o;
    }

    private static double[][] heart(double x, double y, double s) {
        int k = 90;
        double[][] o = new double[k][];
        for (int i = 0; i < k; i++) {
            double t = 2 * Math.PI * i / k;
            double hx = 16 * Math.pow(Math.sin(t), 3), hy = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            o[i] = new double[]{x + hx * s / 17, y - hy * s / 17 - s * 0.08};
        }
        return o;
    }

    /** Cuts a polygon by a convex window (Sutherland–Hodgman). */
    static double[][] clip(double[][] subject, double[][] win) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : subject) out.add(p);
        for (int i = 0; i < win.length && !out.isEmpty(); i++) {
            double[] a = win[i], b = win[(i + 1) % win.length];
            List<double[]> in = out;
            out = new ArrayList<>();
            for (int j = 0; j < in.size(); j++) {
                double[] p = in.get(j), q = in.get((j + 1) % in.size());
                boolean ip = inside(a, b, p), iq = inside(a, b, q);
                if (iq) { if (!ip) out.add(cross(a, b, p, q)); out.add(q); } else if (ip) out.add(cross(a, b, p, q));
            }
        }
        return out.toArray(new double[0][]);
    }

    private static boolean inside(double[] a, double[] b, double[] p) { return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0; }

    private static double[] cross(double[] a, double[] b, double[] p, double[] q) {
        double A1 = q[1] - p[1], B1 = p[0] - q[0], C1 = A1 * p[0] + B1 * p[1];
        double A2 = b[1] - a[1], B2 = a[0] - b[0], C2 = A2 * a[0] + B2 * a[1];
        double d = A1 * B2 - A2 * B1;
        return d == 0 ? p : new double[]{(B2 * C1 - B1 * C2) / d, (A1 * C2 - A2 * C1) / d};
    }

    private static List<double[][]> wedges(int k, double rot, double[][] rect) {
        List<double[][]> out = new ArrayList<>();
        double R = W + H;
        for (int i = 0; i < k; i++) {
            double a1 = rot + 2 * Math.PI * i / k, a2 = rot + 2 * Math.PI * (i + 1) / k;
            double[][] p = new double[10][];
            p[0] = new double[]{CX, CY};
            for (int s = 0; s <= 8; s++) { double a = a1 + (a2 - a1) * s / 8; p[s + 1] = new double[]{CX + R * Math.cos(a), CY + R * Math.sin(a)}; }
            out.add(clip(reverse(p), rect));
        }
        return out;
    }

    private static List<double[][]> strips(double[][] shape, int k, boolean vertical) {
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (double[] p : shape) { x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]); }
        List<double[][]> out = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            double[][] win = vertical
                    ? new double[][]{{x0 + (x1 - x0) * i / k, y0 - 1}, {x0 + (x1 - x0) * (i + 1) / k, y0 - 1}, {x0 + (x1 - x0) * (i + 1) / k, y1 + 1}, {x0 + (x1 - x0) * i / k, y1 + 1}}
                    : new double[][]{{x0 - 1, y0 + (y1 - y0) * i / k}, {x1 + 1, y0 + (y1 - y0) * i / k}, {x1 + 1, y0 + (y1 - y0) * (i + 1) / k}, {x0 - 1, y0 + (y1 - y0) * (i + 1) / k}};
            out.add(clip(shape, win));
        }
        return out;
    }

    private static List<double[][]> tiles(double[][] shape, int k) {
        int rows = k <= 3 ? 1 : k <= 6 ? 2 : 3;
        int base = k / rows, extra = k % rows;
        int[] counts = new int[rows];
        int sum = 0;
        for (int i = 0; i < rows; i++) {
            counts[i] = base + (i >= (rows - extra) / 2.0 && i < (rows - extra) / 2.0 + extra ? 1 : 0);
            sum += counts[i];
        }
        for (int i = 0; sum < k; i++, sum++) counts[i % rows]++;
        double y0 = 1e9, y1 = -1e9;
        for (double[] p : shape) { y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]); }
        List<double[][]> out = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            double[][] band = {{-1, y0 + (y1 - y0) * row / rows}, {W + 1, y0 + (y1 - y0) * row / rows}, {W + 1, y0 + (y1 - y0) * (row + 1) / rows}, {-1, y0 + (y1 - y0) * (row + 1) / rows}};
            out.addAll(strips(clip(shape, band), counts[row], true));
        }
        return out;
    }

    private static List<double[]> edge(double y, boolean zig) {
        List<double[]> pts = new ArrayList<>();
        int teeth = 6;
        for (int j = 0; j <= 48; j++) {
            double x = W * j / 48, t = (j / 48.0) * teeth, amp = H * 0.045;
            double dy = zig ? amp * (2 * Math.abs(t - Math.floor(t) - 0.5) * 2 - 1) : amp * Math.sin(t * 2 * Math.PI);
            pts.add(new double[]{x, y + dy});
        }
        return pts;
    }
}
