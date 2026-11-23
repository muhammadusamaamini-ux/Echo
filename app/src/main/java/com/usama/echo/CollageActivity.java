package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Collage: 2 to 9 photos, many arrangements for each number, any shape, spacing, rounded corners and background. */
public class CollageActivity extends Activity {
    private static final int REQ_PICK = 71, REQ_REPLACE = 72;
    static final String[] ASPECTS = {"1:1", "4:5", "9:16", "3:4", "4:3", "16:9", "2:3", "3:2"};
    static final String[] ASPECT_NAMES = {"Square", "4:5 (post)", "9:16 (story)", "3:4", "4:3", "16:9 (wide)", "2:3", "3:2"};
    static final int[] BG = {0xFFFFFFFF, 0xFF000000, 0xFF1C1C28, 0xFFF4EFE6, 0xFFFFD6E0, 0xFFD6ECFF, 0xFFE3F7D4, 0xFFFFE9B3, 0xFF7B5CFF, 0xFFFF5C8A};

    static final class Cell {
        int p;
        float zoom = 1, ox, oy;
    }

    private final List<Uri> uris = new ArrayList<>();
    private final List<Bitmap> bmps = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private int layout = 0, bg = 0xFFFFFFFF, sel = -1;
    private String aspect = "1:1";
    private float gap = 12, radius = 0;
    private boolean swapping;
    private CollageView cv;
    private FrameLayout panel;
    private TextView count;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(0xFF07070F);
        LinearLayout bar = Ui.topBar(this, "Collage", R.drawable.ic_back);
        TextView add = Ui.pill(this, "+ Photos", false);
        add.setOnClickListener(v -> pick(REQ_PICK, true));
        TextView save = Ui.pill(this, "Save", true);
        save.setOnClickListener(v -> save());
        bar.addView(add);
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 6), 1));
        bar.addView(save);
        root.addView(bar);
        count = Ui.text(this, "", 13, Ui.SUB, false);
        count.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(count);
        cv = new CollageView(this);
        root.addView(cv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel = new FrameLayout(this);
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 230)));
        setContentView(root);
        ArrayList<String> start = getIntent().getStringArrayListExtra("uris");
        List<Uri> list = new ArrayList<>();
        if (start != null) for (String s : start) list.add(Uri.parse(s));
        if (list.isEmpty()) pick(REQ_PICK, true);
        addPhotos(list);
    }

    private void pick(int req, boolean many) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (many) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, many ? "Choose 2 to 9 photos" : "Choose a photo"), req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        List<Uri> got = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) got.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) got.add(data.getData());
        if (req == REQ_PICK) addPhotos(got);
        else if (req == REQ_REPLACE && !got.isEmpty() && sel >= 0) {
            final Cell c = cells.get(sel);
            final Uri u = got.get(0);
            new Thread(() -> {
                Bitmap bmp = Photos.load(this, u, 1600);
                runOnUiThread(() -> {
                    if (bmp == null) return;
                    uris.set(c.p, u);
                    bmps.set(c.p, bmp);
                    c.zoom = 1; c.ox = 0; c.oy = 0;
                    cv.invalidate();
                });
            }).start();
        }
    }

    private void addPhotos(List<Uri> list) {
        if (list.isEmpty()) { refreshAll(); return; }
        count.setText("Loading photos…");
        new Thread(() -> {
            List<Uri> okU = new ArrayList<>();
            List<Bitmap> okB = new ArrayList<>();
            for (Uri u : list) {
                if (uris.size() + okU.size() >= 9) break;
                if (uris.contains(u)) continue;
                Bitmap bmp = Photos.load(this, u, 1600);
                if (bmp != null) { okU.add(u); okB.add(bmp); }
            }
            runOnUiThread(() -> {
                if (uris.size() + list.size() > 9) Toast.makeText(this, "A collage can have up to 9 photos", Toast.LENGTH_SHORT).show();
                uris.addAll(okU);
                bmps.addAll(okB);
                resetCells();
            });
        }).start();
    }

    private void resetCells() {
        cells.clear();
        for (int i = 0; i < bmps.size(); i++) { Cell c = new Cell(); c.p = i; cells.add(c); }
        layout = Math.min(layout, Math.max(0, allLayouts().size() - 1));
        sel = -1;
        swapping = false;
        refreshAll();
    }

    private void refreshAll() {
        int n = bmps.size();
        count.setText(n < 2 ? (n == 0 ? "Add 2 to 9 photos (+ Photos)." : "Add at least one more photo (+ Photos).") : n + " photos  ·  tap a box to change it, drag to move its photo, pinch to zoom");
        fillPanel();
        cv.invalidate();
    }

    // ---------- drawing ----------
    private int[] size(int longSide) {
        String[] p = aspect.split(":");
        float a = Float.parseFloat(p[0]), b = Float.parseFloat(p[1]);
        return a >= b ? new int[]{longSide, Math.round(longSide * b / a)} : new int[]{Math.round(longSide * a / b), longSide};
    }

    /** An arrangement: boxes (rects) or shapes (circles, flower, heart…). */
    static final class Lay {
        float[][] rects;
        CShapes.Layout shape;
    }

    /** Where a photo goes, in pixels. */
    static final class Geo {
        RectF box;          // the photo's area (for shapes: around the shape)
        Path path;          // shapes only
        float[][] pts;      // shapes only
        boolean card;
    }

    private List<Lay> layCache;
    private String layKey = "";

    private List<Lay> allLayouts() {
        String key = bmps.size() + "|" + aspect;
        if (layCache != null && key.equals(layKey)) return layCache; // worked out once per number of photos and shape
        layKey = key;
        List<Lay> out = new ArrayList<>();
        layCache = out;
        for (float[][] r : Photos.layouts(bmps.size())) { Lay l = new Lay(); l.rects = r; out.add(l); }
        int[] s = size(1000);
        for (CShapes.Layout sh : CShapes.layouts(bmps.size(), (double) s[0] / s[1])) { Lay l = new Lay(); l.shape = sh; out.add(l); }
        return out;
    }

    private int boxCount() { return Photos.layouts(bmps.size()).size(); }

    private List<Geo> cells(float W, float H, Lay lay, float g) {
        List<Geo> out = new ArrayList<>();
        if (lay == null) return out;
        if (lay.rects != null) {
            for (float[] r : lay.rects) {
                Geo o = new Geo();
                float l = r[0] * (W - g) + g, t = r[1] * (H - g) + g;
                o.box = new RectF(l, t, l + r[2] * (W - g) - g, t + r[3] * (H - g) - g);
                out.add(o);
            }
            return out;
        }
        for (CShapes.Cell c : lay.shape.cells) {
            Geo o = new Geo();
            o.card = c.card;
            o.pts = new float[c.pts.length][];
            o.path = new Path();
            float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
            for (int i = 0; i < c.pts.length; i++) {
                float x = c.pts[i][0] * W, y = c.pts[i][1] * H;
                o.pts[i] = new float[]{x, y};
                if (i == 0) o.path.moveTo(x, y); else o.path.lineTo(x, y);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
            o.path.close();
            o.box = new RectF(x0, y0, x1, y1);
            out.add(o);
        }
        return out;
    }

    private List<Geo> cells(float W, float H) {
        List<Lay> all = allLayouts();
        if (all.isEmpty()) return new ArrayList<>();
        return cells(W, H, all.get(Math.min(layout, all.size() - 1)), gap / 1000f * Math.min(W, H));
    }

    private static boolean inside(Geo c, float px, float py) {
        if (c.pts == null) return c.box.contains(px, py);
        boolean in = false;
        for (int i = 0, j = c.pts.length - 1; i < c.pts.length; j = i++) {
            float xi = c.pts[i][0], yi = c.pts[i][1], xj = c.pts[j][0], yj = c.pts[j][1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private void drawCollage(Canvas c, float W, float H, boolean forSave) {
        c.drawColor(bg);
        float rad = radius / 1000f * Math.min(W, H), g = gap / 1000f * Math.min(W, H);
        List<Geo> geo = cells(W, H);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < geo.size() && i < cells.size(); i++) {
            Geo o = geo.get(i);
            Cell cell = cells.get(i);
            Bitmap bmp = bmps.get(cell.p);
            RectF r = new RectF(o.box);
            c.save();
            Path clip;
            if (o.path == null) {
                clip = new Path();
                clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            } else if (o.card) { // a photo print: white frame with a soft shadow
                Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
                frame.setColor(Color.WHITE);
                frame.setShadowLayer(Math.min(W, H) * 0.02f, 0, Math.min(W, H) * 0.006f, 0x59000000);
                c.drawPath(o.path, frame);
                float cx = 0, cy = 0;
                for (float[] pt : o.pts) { cx += pt[0] / o.pts.length; cy += pt[1] / o.pts.length; }
                clip = new Path();
                float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
                for (int k = 0; k < o.pts.length; k++) {
                    float x = cx + (o.pts[k][0] - cx) * 0.9f, y = cy + (o.pts[k][1] - cy) * 0.9f;
                    if (k == 0) clip.moveTo(x, y); else clip.lineTo(x, y);
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
                clip.close();
                r = new RectF(x0, y0, x1, y1);
            } else clip = o.path;
            c.clipPath(clip);
            float k = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight()) * cell.zoom;
            float dw = bmp.getWidth() * k, dh = bmp.getHeight() * k;
            float ex = (dw - r.width()) / 2, ey = (dh - r.height()) / 2;
            float left = r.left - ex + cell.ox * ex, top = r.top - ey + cell.oy * ey;
            c.drawBitmap(bmp, null, new RectF(left, top, left + dw, top + dh), p);
            c.restore();
            if (o.path != null && !o.card && g > 0) { // spacing between shapes
                Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
                gp.setStyle(Paint.Style.STROKE);
                gp.setStrokeJoin(Paint.Join.ROUND);
                gp.setStrokeWidth(g);
                gp.setColor(bg);
                c.drawPath(o.path, gp);
            }
            if (!forSave && i == sel) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(Ui.dp(this, 3));
                sp.setColor(swapping ? 0xFFFFD400 : Ui.ACCENT);
                sp.setPathEffect(new DashPathEffect(new float[]{16, 10}, 0));
                if (o.path != null) c.drawPath(o.path, sp); else c.drawRoundRect(o.box, rad, rad, sp);
            }
        }
    }

    private class CollageView extends View {
        private final RectF area = new RectF();
        private final ScaleGestureDetector scaler;
        private int dragCell = -1;
        private float lx, ly;
        private boolean moved;

        CollageView(Context c) {
            super(c);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    if (sel < 0) return true;
                    Cell cell = cells.get(sel);
                    cell.zoom = Math.max(1, Math.min(4, cell.zoom * d.getScaleFactor()));
                    moved = true;
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmps.size() < 2) {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(Ui.SUB);
                t.setTextSize(Ui.dp(getContext(), 15));
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText(bmps.isEmpty() ? "Add 2 to 9 photos" : "Add at least one more photo", getWidth() / 2f, getHeight() / 2f, t);
                return;
            }
            int[] s = size(1000);
            float pad = Ui.dp(getContext(), 12);
            float k = Math.min((getWidth() - 2 * pad) / s[0], (getHeight() - 2 * pad) / s[1]);
            float W = s[0] * k, H = s[1] * k;
            area.set((getWidth() - W) / 2, (getHeight() - H) / 2, (getWidth() + W) / 2, (getHeight() + H) / 2);
            c.save();
            c.translate(area.left, area.top);
            drawCollage(c, W, H, false);
            c.restore();
        }

        private int hit(float x, float y) {
            List<Geo> g = cells(area.width(), area.height());
            for (int i = g.size() - 1; i >= 0; i--) if (inside(g.get(i), x - area.left, y - area.top)) return i; // the top one first
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmps.size() < 2) return true;
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int i = hit(e.getX(), e.getY());
                    moved = false;
                    lx = e.getX(); ly = e.getY();
                    if (i < 0) { sel = -1; swapping = false; fillPanel(); invalidate(); return true; }
                    if (swapping && sel >= 0 && i != sel) {
                        Cell a = cells.get(sel);
                        cells.set(sel, cells.get(i));
                        cells.set(i, a);
                        swapping = false;
                        sel = i;
                        fillPanel();
                        invalidate();
                        return true;
                    }
                    if (sel != i) { sel = i; swapping = false; fillPanel(); }
                    dragCell = i;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragCell < 0 || e.getPointerCount() > 1 || scaler.isInProgress()) return true;
                    Cell cell = cells.get(dragCell);
                    Bitmap bmp = bmps.get(cell.p);
                    RectF r = cells(area.width(), area.height()).get(dragCell).box;
                    float k = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight()) * cell.zoom;
                    float ex = Math.max(1, (bmp.getWidth() * k - r.width()) / 2), ey = Math.max(1, (bmp.getHeight() * k - r.height()) / 2);
                    cell.ox = Math.max(-1, Math.min(1, cell.ox + (e.getX() - lx) / ex));
                    cell.oy = Math.max(-1, Math.min(1, cell.oy + (e.getY() - ly) / ey));
                    lx = e.getX(); ly = e.getY();
                    moved = true;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragCell = -1;
                    if (moved) fillPanel();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** A small picture of an arrangement. */
    private class LayoutThumb extends View {
        final Lay lay;
        final boolean on;

        LayoutThumb(Context c, Lay lay, boolean on) { super(c); this.lay = lay; this.on = on; }

        @Override
        protected void onDraw(Canvas c) {
            Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgp.setColor(on ? Ui.ACCENT : 0x33FFFFFF);
            float w = getWidth(), h = getHeight(), g = Ui.dp(getContext(), 2);
            c.drawRoundRect(0, 0, w, h, g * 2, g * 2, bgp);
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
            box.setColor(on ? 0xFF003A44 : 0xFF6E63C8);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(g * 0.6f);
            edge.setColor(0xFF12121F);
            c.save();
            c.translate(g, g);
            for (Geo o : cells(w - 2 * g, h - 2 * g, lay, g * 0.8f)) {
                if (o.path == null) c.drawRoundRect(o.box, g, g, box);
                else {
                    if (o.card) { Paint cp = new Paint(box); cp.setColor(0xDDFFFFFF); c.drawPath(o.path, cp); } else c.drawPath(o.path, box);
                    c.drawPath(o.path, edge);
                }
            }
            c.restore();
        }
    }

    // ---------- panel ----------
    private interface IntSet { void set(int v); }

    private View slider(String label, int max, int value, IntSet on) {
        LinearLayout c = Ui.column(this);
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, label, 13, Ui.TEXT, false), Ui.weight());
        final TextView val = Ui.text(this, String.valueOf(value), 13, Ui.SUB, false);
        r.addView(val);
        c.addView(r);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { val.setText(String.valueOf(p)); on.set(p); cv.invalidate(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(sb);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return r;
    }

    private HorizontalScrollView hscroll(View v) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(v);
        return h;
    }

    private void addPill(LinearLayout row, String label, boolean on, View.OnClickListener l) {
        TextView p = Ui.pill(this, label, on);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(p, lp);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, true);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return t;
    }

    private void fillPanel() {
        panel.removeAllViews();
        if (bmps.size() < 2) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
        sv.addView(box);
        panel.addView(sv);
        if (sel >= 0 && sel < cells.size()) { // the chosen box
            final Cell c = cells.get(sel);
            box.addView(label("THIS BOX"));
            LinearLayout r = row();
            addPill(r, swapping ? "Now tap the other box…" : "⇄ Swap", swapping, v -> { swapping = !swapping; fillPanel(); cv.invalidate(); });
            addPill(r, "Replace photo", false, v -> pick(REQ_REPLACE, false));
            addPill(r, "Fit again", false, v -> { c.zoom = 1; c.ox = 0; c.oy = 0; cv.invalidate(); });
            addPill(r, "Remove photo", false, v -> { bmps.remove(c.p); uris.remove(c.p); resetCells(); });
            addPill(r, "Done", true, v -> { sel = -1; swapping = false; fillPanel(); cv.invalidate(); });
            box.addView(hscroll(r));
        }
        List<Lay> lays = allLayouts();
        int nBox = boxCount();
        for (int section = 0; section < 2; section++) {
            box.addView(label(section == 0 ? "BOXES (" + nBox + ")" : "SHAPES (" + (lays.size() - nBox) + ") — CIRCLES, FLOWER, HEART, STAR, WAVES…"));
            LinearLayout lr = row();
            int from = section == 0 ? 0 : nBox, to = section == 0 ? nBox : lays.size();
            for (int i = from; i < to; i++) {
                final int k = i;
                LayoutThumb t = new LayoutThumb(this, lays.get(i), i == layout);
                if (lays.get(i).shape != null) t.setContentDescription(lays.get(i).shape.name);
                t.setOnClickListener(v -> {
                    layout = k;
                    fillPanel();
                    cv.invalidate();
                    if (lays.get(k).shape != null) Toast.makeText(this, lays.get(k).shape.name, Toast.LENGTH_SHORT).show();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54));
                lp.rightMargin = Ui.dp(this, 8);
                lr.addView(t, lp);
            }
            box.addView(hscroll(lr));
        }
        box.addView(label("SHAPE"));
        LinearLayout ar = row();
        for (int i = 0; i < ASPECTS.length; i++) {
            final String a = ASPECTS[i];
            addPill(ar, ASPECT_NAMES[i], a.equals(aspect), v -> { aspect = a; fillPanel(); cv.invalidate(); });
        }
        box.addView(hscroll(ar));
        box.addView(slider("Spacing", 60, Math.round(gap), v -> gap = v));
        box.addView(slider("Rounded corners", 60, Math.round(radius), v -> radius = v));
        box.addView(label("BACKGROUND"));
        LinearLayout br = row();
        for (int col : BG) {
            View sw = new View(this);
            sw.setBackground(Ui.round(col, Ui.dp(this, 14)));
            if (col == bg) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke(Ui.dp(this, 3), Ui.ACCENT);
                sw.setForeground(ring);
            }
            sw.setOnClickListener(v -> { bg = col; fillPanel(); cv.invalidate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            lp.rightMargin = Ui.dp(this, 8);
            br.addView(sw, lp);
        }
        box.addView(hscroll(br));
    }

    // ---------- saving ----------
    private void save() {
        if (bmps.size() < 2) { Toast.makeText(this, "Add 2 to 9 photos first", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 73);
            return;
        }
        String[] sizes = {"Large (2048 px)", "Small (1080 px, for sharing)", "Extra large (3000 px)"};
        final int[] px = {2048, 1080, 3000};
        new android.app.AlertDialog.Builder(this).setTitle("Save the collage").setItems(sizes, (d, which) -> {
            Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try {
                    int[] s = size(px[which]);
                    Bitmap out = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.ARGB_8888);
                    drawCollage(new Canvas(out), s[0], s[1], true);
                    File tmp = new File(getCacheDir(), "collage.jpg");
                    try (FileOutputStream fo = new FileOutputStream(tmp)) { out.compress(Bitmap.CompressFormat.JPEG, 93, fo); }
                    String name = "Collage " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date()) + ".jpg";
                    Gallery.save(this, tmp, name, "image/jpeg", Environment.DIRECTORY_PICTURES);
                    tmp.delete();
                    msg = "Saved to Pictures › Echo";
                } catch (Throwable e) {
                    msg = "Couldn't save: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }).start();
        }).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 73 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) save();
    }
}
